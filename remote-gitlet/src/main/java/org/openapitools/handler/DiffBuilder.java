package org.openapitools.handler;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import dto.DiffData;
import gitlet.commit.Commit;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import po.BranchPointer;
import po.Repository;
import po.User;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * PR 差异构建器
 * 比较 target（仓库分支）与 source（合并分支）tip 的追踪文件，
 * 对每个内容变化的文件生成 unified diff，供 AI 审查场景使用
 */
@Slf4j
@Component
public class DiffBuilder {

    private static final int CONTEXT_LINES = 3;
    private static final int MAX_DIFF_CHARS = 50_000;

    @Autowired
    private RemoteBranchPointerMapper branchPointerMapper;

    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;

    @Autowired
    private RemoteUserMapper remoteUserMapper;

    @Value("${storage.base-path:${user.dir}}")
    private String basePath;

    /**
     * 构建 PR 的结构化差异；分支/提交缺失时返回空列表（AI 拿不到 diff 也能继续走 prompt）
     */
    public List<DiffData> buildPrDiff(Long repositoryId, String sourceBranch, String targetBranch) {
        RepositoryPaths paths = getRepositoryPaths(repositoryId);
        if (paths == null) {
            log.warn("buildPrDiff 失败: getRepositoryPaths 返回 null, repositoryId={}", repositoryId);
            return List.of();
        }
        log.info("buildPrDiff 路径: commits={}, object={}", paths.getCommits(), paths.getObject());
        
        BranchPointer source = branchPointerMapper.getBranchPointer(repositoryId, sourceBranch);
        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        if (source == null || target == null
                || source.getCommitSha() == null || target.getCommitSha() == null) {
            log.warn("分支指针缺失: repoId={}, source={}, target={}, sourcePointer={}, targetPointer={}", 
                     repositoryId, sourceBranch, targetBranch, source, target);
            return List.of();
        }
        log.info("分支指针: source sha={}, target sha={}", source.getCommitSha(), target.getCommitSha());
        
        Commit sourceCommit = readCommit(paths, source.getCommitSha());
        Commit targetCommit = readCommit(paths, target.getCommitSha());
        if (sourceCommit == null || targetCommit == null) {
            log.warn("Commit 读取失败: sourceCommit={}, targetCommit={}", sourceCommit, targetCommit);
            return List.of();
        }
        log.info("Commit 读取成功: source trackedFiles={}, target trackedFiles={}", 
                 sourceCommit.getTrackedFiles().size(), targetCommit.getTrackedFiles().size());

        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(sourceCommit.getTrackedFiles().keySet());
        allFiles.addAll(targetCommit.getTrackedFiles().keySet());
        log.info("allFiles size={}", allFiles.size());

        List<DiffData> result = new ArrayList<>();
        for (String fileName : allFiles) {
            String sourceSha = sourceCommit.getTrackedFiles().get(fileName);
            String targetSha = targetCommit.getTrackedFiles().get(fileName);
            if (Objects.equals(sourceSha, targetSha)) {
                continue;
            }
            String oldContent = targetSha == null ? "" : readBlob(paths, targetSha);
            String newContent = sourceSha == null ? "" : readBlob(paths, sourceSha);
            result.add(DiffData.builder()
                    .file_path(fileName)
                    .old_content(oldContent)
                    .new_content(newContent)
                    .diff(buildUnifiedDiff(fileName, oldContent, newContent))
                    .build());
        }
        log.info("buildPrDiff 完成: result size={}", result.size());
        return result;
    }

    /**
     * 生成标准 unified diff（--- a/x +++ b/x @@ 头 + 上下文行）
     */
    private String buildUnifiedDiff(String fileName, String oldContent, String newContent) {
        if (isBinary(oldContent) || isBinary(newContent)) {
            return "(binary file, diff omitted)";
        }
        List<String> oldLines = oldContent.lines().toList();
        List<String> newLines = newContent.lines().toList();
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + fileName, "b/" + fileName, oldLines, patch, CONTEXT_LINES);
        String diff = String.join("\n", unified);
        if (diff.length() > MAX_DIFF_CHARS) {
            return diff.substring(0, MAX_DIFF_CHARS) + "\n... (diff truncated)";
        }
        return diff;
    }

    private boolean isBinary(String content) {
        return content.indexOf('\0') >= 0;
    }

    private Commit readCommit(RepositoryPaths paths, String commitSha) {
        File commitFile = new File(paths.getCommits(), commitSha);
        if (!commitFile.exists()) {
            log.error("Commit file not found: {}", commitFile.getAbsolutePath());
            return null;
        }
        return Utils.readObject(commitFile, Commit.class);
    }

    private String readBlob(RepositoryPaths paths, String blobSha) {
        File blobFile = new File(paths.getObject(), blobSha);
        if (!blobFile.exists()) {
            log.error("Blob file not found: {}", blobFile.getAbsolutePath());
            return "";
        }
        return Utils.readContentsAsString(blobFile);
    }

    private RepositoryPaths getRepositoryPaths(Long repositoryId) {
        Repository repository = remoteRepositoryMapper.selectById(repositoryId);
        if (repository == null) {
            log.error("Repository not found: {}", repositoryId);
            return null;
        }
        User owner = remoteUserMapper.selectById(repository.getOwnerId());
        if (owner == null) {
            log.error("Owner not found: {}", repository.getOwnerId());
            return null;
        }
        return new RepositoryPaths(owner.getUsername() + "/" + repository.getName(), new File(basePath));
    }
}
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
import vo.PrDiffFileVO;
import vo.PrDiffLineVO;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR 差异构建器
 * 比较 target（仓库分支）与 source（合并分支）tip 的追踪文件，
 * 对每个内容变化的文件生成 unified diff，供 AI 审查与前端行级评论使用
 */
@Slf4j
@Component
public class DiffBuilder {

    private static final int CONTEXT_LINES = 3;
    private static final int MAX_DIFF_CHARS = 50_000;
    /** 单文件给前端渲染的行数上限，防止超大 diff 拖垮页面 */
    private static final int MAX_VIEW_LINES = 5_000;
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

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
        BranchCommits commits = resolveBranchCommits(repositoryId, sourceBranch, targetBranch);
        if (commits == null) {
            return List.of();
        }
        List<DiffData> result = new ArrayList<>();
        for (FileChange change : collectChanges(commits)) {
            result.add(DiffData.builder()
                    .file_path(change.path())
                    .old_content(change.oldContent())
                    .new_content(change.newContent())
                    .diff(buildUnifiedDiff(change.path(), change.oldContent(), change.newContent()))
                    .build());
        }
        log.info("buildPrDiff 完成: result size={}", result.size());
        return result;
    }

    /**
     * 构建前端渲染用的 PR 差异：保留每个文件的新旧行号，供行级评论定位。
     */
    public List<PrDiffFileVO> buildPrDiffView(Long repositoryId, String sourceBranch, String targetBranch) {
        BranchCommits commits = resolveBranchCommits(repositoryId, sourceBranch, targetBranch);
        if (commits == null) {
            return List.of();
        }
        List<PrDiffFileVO> result = new ArrayList<>();
        for (FileChange change : collectChanges(commits)) {
            boolean binary = isBinary(change.oldContent()) || isBinary(change.newContent());
            List<PrDiffLineVO> lines = binary
                    ? List.of()
                    : parseUnifiedLines(change.path(), change.oldContent(), change.newContent());
            boolean truncated = lines.size() > MAX_VIEW_LINES;
            if (truncated) {
                lines = new ArrayList<>(lines.subList(0, MAX_VIEW_LINES));
            }
            result.add(PrDiffFileVO.builder()
                    .path(change.path())
                    .changeType(change.changeType())
                    .oldSha(change.oldSha())
                    .newSha(change.newSha())
                    .binary(binary)
                    .truncated(truncated)
                    .lines(lines)
                    .build());
        }
        log.info("buildPrDiffView 完成: result size={}", result.size());
        return result;
    }

    /**
     * 取 source / target 分支 tip 提交；任一步缺失都返回 null
     */
    private BranchCommits resolveBranchCommits(Long repositoryId, String sourceBranch, String targetBranch) {
        RepositoryPaths paths = getRepositoryPaths(repositoryId);
        if (paths == null) {
            log.warn("buildPrDiff 失败: getRepositoryPaths 返回 null, repositoryId={}", repositoryId);
            return null;
        }
        log.info("buildPrDiff 路径: commits={}, object={}", paths.getCommits(), paths.getObject());

        BranchPointer source = branchPointerMapper.getBranchPointer(repositoryId, sourceBranch);
        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        if (source == null || target == null
                || source.getCommitSha() == null || target.getCommitSha() == null) {
            log.warn("分支指针缺失: repoId={}, source={}, target={}, sourcePointer={}, targetPointer={}",
                    repositoryId, sourceBranch, targetBranch, source, target);
            return null;
        }
        log.info("分支指针: source sha={}, target sha={}", source.getCommitSha(), target.getCommitSha());

        Commit sourceCommit = readCommit(paths, source.getCommitSha());
        Commit targetCommit = readCommit(paths, target.getCommitSha());
        if (sourceCommit == null || targetCommit == null) {
            log.warn("Commit 读取失败: sourceCommit={}, targetCommit={}", sourceCommit, targetCommit);
            return null;
        }
        log.info("Commit 读取成功: source trackedFiles={}, target trackedFiles={}",
                sourceCommit.getTrackedFiles().size(), targetCommit.getTrackedFiles().size());
        return new BranchCommits(paths, sourceCommit, targetCommit);
    }

    /**
     * 逐文件比较 source / target 追踪的 blob，返回内容有变化的文件
     */
    private List<FileChange> collectChanges(BranchCommits commits) {
        Commit sourceCommit = commits.source();
        Commit targetCommit = commits.target();
        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(sourceCommit.getTrackedFiles().keySet());
        allFiles.addAll(targetCommit.getTrackedFiles().keySet());
        log.info("allFiles size={}", allFiles.size());

        List<FileChange> changes = new ArrayList<>();
        for (String fileName : allFiles) {
            String sourceSha = sourceCommit.getTrackedFiles().get(fileName);
            String targetSha = targetCommit.getTrackedFiles().get(fileName);
            if (Objects.equals(sourceSha, targetSha)) {
                continue;
            }
            String oldContent = targetSha == null ? "" : readBlob(commits.paths(), targetSha);
            String newContent = sourceSha == null ? "" : readBlob(commits.paths(), sourceSha);
            changes.add(new FileChange(fileName, targetSha, sourceSha, oldContent, newContent));
        }
        return changes;
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

    /**
     * 把 unified diff 拆成带新旧行号的行，跳过文件头与 hunk 头。
     */
    private List<PrDiffLineVO> parseUnifiedLines(String fileName, String oldContent, String newContent) {
        List<String> oldLines = oldContent.lines().toList();
        List<String> newLines = newContent.lines().toList();
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + fileName, "b/" + fileName, oldLines, patch, CONTEXT_LINES);

        List<PrDiffLineVO> lines = new ArrayList<>();
        int oldLine = 0;
        int newLine = 0;
        for (String line : unified) {
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (hunk.matches()) {
                oldLine = Integer.parseInt(hunk.group(1));
                newLine = Integer.parseInt(hunk.group(2));
                continue;
            }
            if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("+")) {
                lines.add(new PrDiffLineVO("add", null, newLine++, line.substring(1)));
            } else if (line.startsWith("-")) {
                lines.add(new PrDiffLineVO("del", oldLine++, null, line.substring(1)));
            } else if (line.startsWith(" ")) {
                lines.add(new PrDiffLineVO("context", oldLine++, newLine++, line.substring(1)));
            }
        }
        return lines;
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

    private record BranchCommits(RepositoryPaths paths, Commit source, Commit target) {
    }

    private record FileChange(String path, String oldSha, String newSha,
                              String oldContent, String newContent) {

        String changeType() {
            if (oldSha == null) {
                return "ADDED";
            }
            if (newSha == null) {
                return "DELETED";
            }
            return "MODIFIED";
        }
    }
}

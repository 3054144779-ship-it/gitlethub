package org.openapitools.handler;

import common.Result;
import common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteCommitPointerMapper;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import gitlet.commit.Commit;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.utils.StagingTombstone;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import po.BranchPointer;
import po.PullRequest;
import po.Repository;
import po.User;

import java.io.File;
import java.io.Serializable;
import java.sql.Wrapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 远程合并处理器
 * 负责 PR 合并时的服务端三路合并
 * 从对象库读取 commit/blob，内存中执行合并，结果写回对象库
 */
@Slf4j
@Component
public class MergeHandler {

    @Autowired
    private RemoteBranchPointerMapper branchPointerMapper;

    @Autowired
    private RemoteCommitPointerMapper commitPointerMapper;
    
    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;

    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;

    @Autowired
    private RemoteUserMapper remoteUserMapper;

    @Value("${storage.base-path:${user.dir}}")
    private String basePath;

    /**
     * 冲突标记检测正则（行首锚定）
     * 匹配三种情况：以 "<<<<<<< " 开头的行、整行恰为 "=======" 的行、以 ">>>>>>> " 开头的行
     * 行首锚定可避免把正常文件里的 "======="（如 Markdown 分隔线）误判为冲突
     */
    private static final Pattern CONFLICT_MARKER_PATTERN =
            Pattern.compile("^(?:<<<<<<< |=======$|>>>>>>> )", Pattern.MULTILINE);

    /**
     * 合并 source 分支到 target 分支（PR 合并入口）
     * @param repositoryId 仓库 ID
     * @param sourceBranch 源分支名
     * @param targetBranch 目标分支名
     * @param prId PR ID（可选，用于检查暂存区）
     * @return 合并结果
     */
    public Result<String> merge(Long repositoryId, String sourceBranch, String targetBranch, Long prId) {
        // 如果有 PR ID，先检查是否有暂存区
        if (prId != null) {
            PullRequest pr = remotePullRequestMapper.selectById(prId);
            if (pr != null && pr.getStagingPath() != null) {
                log.info("检测到 PR {} 有暂存区，尝试从暂存区恢复合并", prId);
                return mergeFromStaging(repositoryId, pr, sourceBranch, targetBranch);
            }
        }
        
        // 1. 获取分支指针
        BranchPointer source = branchPointerMapper.getBranchPointer(repositoryId, sourceBranch);
        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);

        if (source == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND, "Source branch '" + sourceBranch + "' does not exist.");
        }
        if (target == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND, "Target branch '" + targetBranch + "' does not exist.");
        }

        String sourceSha = source.getCommitSha();
        String targetSha = target.getCommitSha();

        if (sourceSha == null || targetSha == null) {
            return Result.fail(ResultCode.BRANCH_EMPTY, "该分支目前尚无提交。");
        }

        // 不能合并自身
        if (sourceBranch.equals(targetBranch)) {
            return Result.fail(ResultCode.CANNOT_MERGE_WITH_ITSELF, "Cannot merge a branch with itself.");
        }

        log.info("Merging branches: {} -> {}", sourceBranch, targetBranch);
        // 2. 找 LCA（遍历 commit 对象的父链，不依赖不完整的 commit_parent 表）
        RepositoryPaths paths = getRepositoryPaths(repositoryId);
        if (paths == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "无法获取仓库路径。");
        }
        log.info("sourceSha: {}, targetSha: {}", sourceSha, targetSha);
        String lcaSha = findLCA(paths, sourceSha, targetSha);
        if (lcaSha == null) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, "拒绝合并无关的历史记录。");
        }

        // 3. 判断合并类型
        if (lcaSha.equals(sourceSha)) {
            log.info("源分支是目标分支的祖先，不需要执行合并操作: {} -> {}", sourceBranch, targetBranch);
            log.info("lcaSha:{}, sourceSha:{}", lcaSha, sourceSha);
            // source 是 target 的祖先，target 已包含 source 的所有提交
            return Result.ok(targetSha + " 已经更新完毕。");
        }

        if (lcaSha.equals(targetSha)) {
            // 快进：target 是 source 的祖先，直接将 target 指向 source
            target.setCommitSha(sourceSha);
            target.setUpdateTime(LocalDateTime.now());

            BranchPointer branchPointer = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
            int line = branchPointerMapper.updateBranchPointer(branchPointer.getId(), sourceSha, target.getVersion());
            if (line <= 0) {
                return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, MsgConstant.SYSTEM_ERROR);
            }
            log.info("repostoryId: {}, sourceSha: {}, targetSha: {}", repositoryId, sourceSha, targetSha);
            log.info("影响了 {} 行", line);
            log.info("快进合并: {} -> {}, 新 SHA: {}", sourceBranch, targetBranch, sourceSha);
            return Result.ok(sourceSha + " 快进合并成功。");
        }

        // 4. 非快进合并：执行三路合并
        log.info("三路合并: {} -> {}, LCA: {}", sourceBranch, targetBranch, lcaSha);
        return threeWayMerge(repositoryId, paths, sourceSha, targetSha, lcaSha, sourceBranch, targetBranch);
    }

    /**
     * 兼容旧调用（不带 PR ID）
     */
    public Result<String> merge(Long repositoryId, String sourceBranch, String targetBranch) {
        return merge(repositoryId, sourceBranch, targetBranch, null);
    }

    /**
     * 三路合并核心逻辑
     */
    private Result<String> threeWayMerge(Long repositoryId, RepositoryPaths paths, String sourceSha,
                                          String targetSha, String lcaSha,
                                          String sourceBranch, String targetBranch) {
        Commit sourceCommit = readCommit(paths, sourceSha);
        Commit targetCommit = readCommit(paths, targetSha);
        Commit lcaCommit = readCommit(paths, lcaSha);

        if (sourceCommit == null || targetCommit == null || lcaCommit == null) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, "无法读取 commit 对象。");
        }

        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(sourceCommit.getTrackedFiles().keySet());
        allFiles.addAll(targetCommit.getTrackedFiles().keySet());
        allFiles.addAll(lcaCommit.getTrackedFiles().keySet());

        Map<String, String> mergedTrackedFiles = new TreeMap<>(targetCommit.getTrackedFiles());
        List<String> conflictFiles = new ArrayList<>();

        for (String fileName : allFiles) {
            String sourceBlobSha = sourceCommit.getTrackedFiles().get(fileName);
            String targetBlobSha = targetCommit.getTrackedFiles().get(fileName);
            String lcaBlobSha = lcaCommit.getTrackedFiles().get(fileName);

            if (Objects.equals(sourceBlobSha, targetBlobSha) || Objects.equals(sourceBlobSha, lcaBlobSha)) {
                continue;
            }

            if (Objects.equals(targetBlobSha, lcaBlobSha)) {
                if (sourceBlobSha != null) {
                    mergedTrackedFiles.put(fileName, sourceBlobSha);
                } else {
                    mergedTrackedFiles.remove(fileName);
                }
                continue;
            }

            conflictFiles.add(fileName);
        }

        if (!conflictFiles.isEmpty()) {
            return handleConflict(paths, sourceSha, targetSha, lcaSha,
                    conflictFiles, sourceBranch, targetBranch, mergedTrackedFiles);
        }

        return createMergeCommit(repositoryId, paths, mergedTrackedFiles,
                sourceSha, targetSha, sourceBranch, targetBranch);
    }

    /**
     * 从暂存区恢复合并
     * 读取暂存区的文件，检查是否有冲突标记，无冲突则生成合并提交
     * 注意：不清理暂存区，由调用方（PullRequestEventListenerImpl）负责
     */
    private Result<String> mergeFromStaging(Long repositoryId, PullRequest pr, 
                                             String sourceBranch, String targetBranch) {
        String stagingPath = pr.getStagingPath();
        String stagingPathTracked = pr.getStagingPathTracked();
        File stagingDir = new File(stagingPath);

        // 暂存区目录或 trackedFiles 文件缺失/不完整 → 回退到重新三路合并
        if (stagingPathTracked == null || !stagingDir.exists()) {
            log.warn("暂存区不完整: stagingPath={}, trackedPath={}", stagingPath, stagingPathTracked);
            return merge(repositoryId, sourceBranch, targetBranch, null);
        }

        File stagingPathFile = new File(stagingPathTracked);
        if (!stagingPathFile.exists()) {
            log.warn("trackedFiles 序列化文件不存在: {}", stagingPathTracked);
            return merge(repositoryId, sourceBranch, targetBranch, null);
        }

        // 只有暂存区完整，回复合并才能进行
        log.info("从暂存区恢复合并: {}", stagingPath);

        // 获取仓库路径
        RepositoryPaths paths = getRepositoryPaths(repositoryId);
        if (paths == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "无法获取仓库路径。");
        }
        
        // 读取 source 和 target commit
        BranchPointer source = branchPointerMapper.getBranchPointer(repositoryId, sourceBranch);
        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        String sourceSha = source.getCommitSha();
        String targetSha = target.getCommitSha();
        
        Commit sourceCommit = readCommit(paths, sourceSha);
        Commit targetCommit = readCommit(paths, targetSha);
        
        if (sourceCommit == null || targetCommit == null) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, "无法读取 commit 对象。");
        }
        
        // 标记删除的路径：暂存区里文件仍在（便于撤销），合并时需要从 trackedFiles 中移除
        Set<String> deletedPaths = StagingTombstone.read(stagingDir.toPath());

        // 收集暂存区中的所有文件
        Map<String, String> resolvedFileShas = new HashMap<>();
        List<String> filesWithConflictMarkers = new ArrayList<>();

        // 遍历暂存区目录
        collectResolvedFiles(stagingDir, "", resolvedFileShas, filesWithConflictMarkers, paths, deletedPaths);
        
        // 检查是否有文件仍包含冲突标记
        if (!filesWithConflictMarkers.isEmpty()) {
            log.warn("暂存区中仍有文件包含冲突标记: {}", filesWithConflictMarkers);
            return Result.fail(ResultCode.MERGE_CONFLICT, 
                    "以下文件仍包含冲突标记，请继续解决：" + String.join(", ", filesWithConflictMarkers));
        }
        
        // 构建合并后的 trackedFiles：先叠加解决后的文件，再删掉标记删除的路径
        Map<String, String> mergedTrackedFiles = Utils.readObject(stagingPathFile,  HashMap.class);
        mergedTrackedFiles.putAll(resolvedFileShas);
        mergedTrackedFiles.keySet().removeAll(deletedPaths);
        
        // 创建合并提交
        return createMergeCommitFromStaging(repositoryId, paths, mergedTrackedFiles,
                sourceSha, targetSha, sourceBranch, targetBranch);
    }

    /**
     * 递归收集暂存区中的文件，计算 blob SHA，检查冲突标记
     */
    private void collectResolvedFiles(File dir, String relativePath,
                                       Map<String, String> resolvedFileShas,
                                       List<String> filesWithConflictMarkers,
                                       RepositoryPaths paths,
                                       Set<String> deletedPaths) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            String currentRelativePath = relativePath.isEmpty() ?
                    file.getName() : relativePath + "/" + file.getName();

            // 删除清单不是仓库文件；标记删除的文件不参与冲突标记检查和 blob 写入
            if (file.isFile()
                    && (StagingTombstone.FILE_NAME.equals(file.getName()) || deletedPaths.contains(currentRelativePath))) {
                continue;
            }

            if (file.isDirectory()) {
                collectResolvedFiles(file, currentRelativePath, resolvedFileShas,
                        filesWithConflictMarkers, paths, deletedPaths);
            } else {
                String content = Utils.readContentsAsString(file);

                // 检查是否包含冲突标记（行首锚定，避免误判正常文件里的 =======）
                if (CONFLICT_MARKER_PATTERN.matcher(content).find()) {
                    filesWithConflictMarkers.add(currentRelativePath);
                    continue;
                }
                
                // 计算 blob SHA
                String blobSha = Utils.sha1(content);
                
                // 保存 blob 到对象库
                File blobFile = new File(paths.getObject(), blobSha);
                Utils.writeContents(blobFile, content);
                
                resolvedFileShas.put(currentRelativePath, blobSha);
                log.debug("暂存区文件: {} -> {}", currentRelativePath, blobSha);
            }
        }
    }

    /**
     * 从暂存区文件创建合并提交
     * 注意：不清理暂存区，由调用方负责
     */
    private Result<String> createMergeCommitFromStaging(Long repositoryId, RepositoryPaths paths,
                                                         Map<String, String> mergedTrackedFiles,
                                                         String sourceSha, String targetSha,
                                                         String sourceBranch, String targetBranch) {
        String message = "Merged " + sourceBranch + " into " + targetBranch + ".";
        List<String> mergeFiles = Arrays.asList(
                targetSha.substring(0, 7),
                sourceSha.substring(0, 7)
        );

        Commit mergeCommit = new Commit(message, mergeFiles, targetSha, sourceSha, mergedTrackedFiles);
        String mergeSha = saveCommit(paths, mergeCommit);

        log.info("创建合并提交: {}", mergeSha);

        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        target.setCommitSha(mergeSha);
        target.setUpdateTime(LocalDateTime.now());
//        int line = branchPointerMapper.updateBranchPointer(repositoryId, mergeSha, target.getVersion());
        log.info("更新分支指针: {}", target.toString());
        int line = branchPointerMapper.updateById(target);

        if (line < 1) {
            log.warn("更新分支指针失败: {}", targetBranch);
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, MsgConstant.REMOTE_REPOSITORY_UPDATE_FAILED);
        }

        commitPointerMapper.insertCommitParent(repositoryId, mergeSha, targetSha);
        commitPointerMapper.insertCommitParent(repositoryId, mergeSha, sourceSha);

        // 注意：这里不清理暂存区，由 PullRequestEventListenerImpl 负责
        log.info("从暂存区恢复合并成功: {} ({} + {})", mergeSha, targetSha, sourceSha);
        return Result.ok(mergeSha + " 合并成功。");
    }

    /**
     * 处理冲突：生成冲突文件到临时目录，返回暂存区路径
     */
    private Result<String> handleConflict(RepositoryPaths paths,
                                           String sourceSha, String targetSha, String lcaSha,
                                           List<String> conflictFiles,
                                           String sourceBranch, String targetBranch,
                                          Map<String, String> mergedTrackedFiles) {
        String tempDir = basePath + "/temp/merge-" + System.currentTimeMillis();
        String tempTrackedDir = basePath + "/temp/tracked" + System.currentTimeMillis();
        File tempDirFile = new File(tempDir);
        File tempTrackedFileDir = new File(tempTrackedDir);
        tempDirFile.mkdirs();

        // 序列化已自动合并的 trackedFiles
        // 用 writeObject（走 serialize）与读取端 readObject 配对；转 HashMap 保证与 HashMap.class 类型一致
        Utils.writeObject(tempTrackedFileDir, new HashMap<>(mergedTrackedFiles));

        Commit sourceCommit = readCommit(paths, sourceSha);
        Commit targetCommit = readCommit(paths, targetSha);

        for (String fileName : conflictFiles) {
            String sourceBlobSha = sourceCommit.getTrackedFiles().get(fileName);
            String targetBlobSha = targetCommit.getTrackedFiles().get(fileName);

            String sourceContent = (sourceBlobSha != null) ? readBlob(paths, sourceBlobSha) : "";
            String targetContent = (targetBlobSha != null) ? readBlob(paths, targetBlobSha) : "";

            StringBuilder conflictContent = new StringBuilder();
            conflictContent.append("<<<<<<< ").append(targetBranch).append("\n");
            conflictContent.append(targetContent);
            if (!targetContent.isEmpty() && !targetContent.endsWith("\n")) {
                conflictContent.append("\n");
            }
            conflictContent.append("=======\n");
            conflictContent.append(sourceContent);
            if (!sourceContent.isEmpty() && !sourceContent.endsWith("\n")) {
                conflictContent.append("\n");
            }
            conflictContent.append(">>>>>>> ").append(sourceBranch).append("\n");

            File conflictFile = new File(tempDir, fileName);
            conflictFile.getParentFile().mkdirs();
            Utils.writeContents(conflictFile, conflictContent.toString());
        }

        log.warn("Merge conflict: {} files, temp dir: {}", conflictFiles.size(), tempDir);

        String data = tempDir + "|" + tempTrackedDir;

        String conflictMsg = "合并冲突：" + String.join(", ", conflictFiles);
        return Result.<String>fail(ResultCode.MERGE_CONFLICT, conflictMsg)
                .setData(data);
    }

    /**
     * 创建合并提交并更新分支指针（无冲突场景）
     */
    private Result<String> createMergeCommit(Long repositoryId, RepositoryPaths paths,
                                              Map<String, String> mergedTrackedFiles,
                                              String sourceSha, String targetSha,
                                              String sourceBranch, String targetBranch) {
        String message = "Merged " + sourceBranch + " into " + targetBranch + ".";
        List<String> mergeFiles = Arrays.asList(
                targetSha.substring(0, 7),
                sourceSha.substring(0, 7)
        );

        Commit mergeCommit = new Commit(message, mergeFiles, targetSha, sourceSha, mergedTrackedFiles);
        String mergeSha = saveCommit(paths, mergeCommit);

        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        target.setCommitSha(mergeSha);
        target.setUpdateTime(LocalDateTime.now());
        // 必须按分支指针的主键更新；这里曾经误传 repositoryId，导致合并提交建了但分支指针没动
        int line = branchPointerMapper.updateBranchPointer(target.getId(), mergeSha, target.getVersion());
        if (line < 1) {
            log.warn("更新分支指针失败: {} (id={}, version={})", targetBranch, target.getId(), target.getVersion());
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, MsgConstant.REMOTE_REPOSITORY_UPDATE_FAILED);
        }

        commitPointerMapper.insertCommitParent(repositoryId, mergeSha, targetSha);
        commitPointerMapper.insertCommitParent(repositoryId, mergeSha, sourceSha);

        log.info("Merge commit created: {} ({} + {})", mergeSha, targetSha, sourceSha);
        return Result.ok(mergeSha + " 合并成功。");
    }

    /**
     * 从对象库读取 commit 对象
     */
    private Commit readCommit(RepositoryPaths paths, String commitSha) {
        File commitFile = new File(paths.getCommits(), commitSha);
        if (!commitFile.exists()) {
            log.error("Commit file not found: {}", commitFile.getAbsolutePath());
            return null;
        }
        return Utils.readObject(commitFile, Commit.class);
    }

    /**
     * 从对象库读取 blob 内容
     */
    private String readBlob(RepositoryPaths paths, String blobSha) {
        File blobFile = new File(paths.getObject(), blobSha);
        if (!blobFile.exists()) {
            log.error("Blob file not found: {}", blobFile.getAbsolutePath());
            return "";
        }
        return Utils.readContentsAsString(blobFile);
    }

    /**
     * 保存 commit 到对象库
     * SHA 必须与客户端 ObjectStore.saveCommit 一致：sha1(序列化字节)
     */
    private String saveCommit(RepositoryPaths paths, Commit commit) {
        byte[] serialized = Utils.serialize(commit);
        String sha = Utils.sha1((Object) serialized);
        File commitFile = new File(paths.getCommits(), sha);
        Utils.writeObject(commitFile, commit);
        return sha;
    }

    /**
     * 查找两个提交的最近公共祖先 (LCA)
     * 以 commit 对象内的 parent/secondParent 为准 BFS 遍历提交 DAG。
     * 不依赖 commit_parent 表：该表只记录推送事件的「tip → 分支旧指向」边，
     * 批量推送的中间提交、新分支首推（旧指向为 null）、客户端合并提交的第二父均缺边，
     * 强推还会写入假边
     */
    private String findLCA(RepositoryPaths paths, String sourceSha, String targetSha) {
        // 收集 target 的所有祖先
        Set<String> targetAncestors = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(targetSha);

        while (!queue.isEmpty()) {
            String sha = queue.poll();
            if (sha == null || !targetAncestors.add(sha)) {
                continue;
            }
            queue.addAll(parentShas(paths, sha));
        }

        log.info("Target 祖先: {}", targetAncestors);

        // 从 source 开始 BFS，找第一个在 targetAncestors 中的节点
        queue.clear();
        queue.add(sourceSha);
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String sha = queue.poll();
            if (sha == null || !visited.add(sha)) {
                continue;
            }

            if (targetAncestors.contains(sha)) {
                return sha;
            }

            queue.addAll(parentShas(paths, sha));
        }

        return null;
    }

    /**
     * 反序列化 commit 对象，返回其全部父提交 SHA（第一父 + 第二父）；对象缺失返回空列表
     */
    private List<String> parentShas(RepositoryPaths paths, String sha) {
        Commit commit = readCommit(paths, sha);
        if (commit == null) {
            return Collections.emptyList();
        }
        List<String> parents = new ArrayList<>(2);
        if (commit.getParent() != null) {
            parents.add(commit.getParent());
        }
        if (commit.getSecondParent() != null) {
            parents.add(commit.getSecondParent());
        }
        return parents;
    }

    /**
     * 获取仓库路径（需要 owner 和 repo 名称）
     * 路径格式：{basePath}/.gitlet/{owner}/{repo}/
     */
    private RepositoryPaths getRepositoryPaths(Long repositoryId) {
        // 1. 获取仓库信息
        Repository repository = remoteRepositoryMapper.selectById(repositoryId);
        if (repository == null) {
            log.error("Repository not found: {}", repositoryId);
            return null;
        }

        // 2. 获取 owner 用户名
        User owner = remoteUserMapper.selectById(repository.getOwnerId());
        if (owner == null) {
            log.error("Owner not found: {}", repository.getOwnerId());
            return null;
        }

        // 3. 构造路径：owner/repo
        String repoPath = owner.getUsername() + "/" + repository.getName();
        return new RepositoryPaths(repoPath, new File(basePath));
    }

}

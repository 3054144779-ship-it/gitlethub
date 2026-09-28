package org.openapitools.handler;

import common.Result;
import common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteCommitPointerMapper;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RemoteCommit;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import po.BranchPointer;
import po.PullRequest;
import po.Repository;
import po.User;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

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

    @Value("${gitlet.storage.base-path:./storage}")
    private String basePath;

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

        // 2. 找 LCA
        String lcaSha = findLCA(repositoryId, sourceSha, targetSha);
        if (lcaSha == null) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, "拒绝合并无关的历史记录。");
        }

        // 3. 判断合并类型
        if (lcaSha.equals(sourceSha)) {
            // source 是 target 的祖先，target 已包含 source 的所有提交
            return Result.ok(targetSha + " 已经更新完毕。");
        }

        if (lcaSha.equals(targetSha)) {
            // 快进：target 是 source 的祖先，直接将 target 指向 source
            target.setCommitSha(sourceSha);
            target.setUpdateTime(LocalDateTime.now());
            branchPointerMapper.updateBranchPointer(repositoryId, sourceSha, target.getVersion());
            log.info("Fast-forward merge: {} -> {}, new SHA: {}", sourceBranch, targetBranch, sourceSha);
            return Result.ok(sourceSha + " 快进合并成功。");
        }

        // 4. 非快进合并：执行三路合并
        log.info("Three-way merge: {} -> {}, LCA: {}", sourceBranch, targetBranch, lcaSha);
        return threeWayMerge(repositoryId, sourceSha, targetSha, lcaSha, sourceBranch, targetBranch);
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
    private Result<String> threeWayMerge(Long repositoryId, String sourceSha, String targetSha,
                                          String lcaSha, String sourceBranch, String targetBranch) {
        RepositoryPaths paths = getRepositoryPaths(repositoryId);
        if (paths == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "无法获取仓库路径。");
        }

        RemoteCommit sourceCommit = readCommit(paths, sourceSha);
        RemoteCommit targetCommit = readCommit(paths, targetSha);
        RemoteCommit lcaCommit = readCommit(paths, lcaSha);

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
                    conflictFiles, sourceBranch, targetBranch);
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
        File stagingDir = new File(stagingPath);
        
        if (!stagingDir.exists()) {
            log.warn("暂存区不存在: {}", stagingPath);
            // 暂存区已被清理，重新执行正常合并
            return merge(repositoryId, sourceBranch, targetBranch, null);
        }
        
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
        
        RemoteCommit sourceCommit = readCommit(paths, sourceSha);
        RemoteCommit targetCommit = readCommit(paths, targetSha);
        
        if (sourceCommit == null || targetCommit == null) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, "无法读取 commit 对象。");
        }
        
        // 关键：收集暂存区中的所有文件
        Map<String, String> resolvedFileShas = new HashMap<>();
        List<String> filesWithConflictMarkers = new ArrayList<>();
        
        // 遍历暂存区目录
        collectResolvedFiles(stagingDir, "", resolvedFileShas, filesWithConflictMarkers, paths);
        
        // 检查是否有文件仍包含冲突标记
        if (!filesWithConflictMarkers.isEmpty()) {
            log.warn("暂存区中仍有文件包含冲突标记: {}", filesWithConflictMarkers);
            return Result.fail(ResultCode.MERGE_CONFLICT, 
                    "以下文件仍包含冲突标记，请继续解决：" + String.join(", ", filesWithConflictMarkers));
        }
        
        // 构建合并后的 trackedFiles
        Map<String, String> mergedTrackedFiles = new TreeMap<>(targetCommit.getTrackedFiles());
        mergedTrackedFiles.putAll(resolvedFileShas);
        
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
                                       RepositoryPaths paths) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            String currentRelativePath = relativePath.isEmpty() ? 
                    file.getName() : relativePath + "/" + file.getName();
            
            if (file.isDirectory()) {
                collectResolvedFiles(file, currentRelativePath, resolvedFileShas, 
                        filesWithConflictMarkers, paths);
            } else {
                String content = Utils.readContentsAsString(file);
                
                // 检查是否包含冲突标记
                if (content.contains("<<<<<<<") || content.contains("=======") || 
                    content.contains(">>>>>>>")) {
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

        RemoteCommit mergeCommit = new RemoteCommit(message, mergeFiles, targetSha, sourceSha, mergedTrackedFiles);
        String mergeSha = saveCommit(paths, mergeCommit);

        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        target.setCommitSha(mergeSha);
        target.setUpdateTime(LocalDateTime.now());
        branchPointerMapper.updateBranchPointer(repositoryId, mergeSha, target.getVersion());

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
                                           String sourceBranch, String targetBranch) {
        String tempDir = basePath + "/temp/merge-" + System.currentTimeMillis();
        File tempDirFile = new File(tempDir);
        tempDirFile.mkdirs();

        RemoteCommit sourceCommit = readCommit(paths, sourceSha);
        RemoteCommit targetCommit = readCommit(paths, targetSha);

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
        
        String conflictMsg = "合并冲突：" + String.join(", ", conflictFiles);
        return Result.<String>fail(ResultCode.MERGE_CONFLICT, conflictMsg)
                .setData(tempDir);
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

        RemoteCommit mergeCommit = new RemoteCommit(message, mergeFiles, targetSha, sourceSha, mergedTrackedFiles);
        String mergeSha = saveCommit(paths, mergeCommit);

        BranchPointer target = branchPointerMapper.getBranchPointer(repositoryId, targetBranch);
        target.setCommitSha(mergeSha);
        target.setUpdateTime(LocalDateTime.now());
        branchPointerMapper.updateBranchPointer(repositoryId, mergeSha, target.getVersion());

        commitPointerMapper.insertCommitParent(repositoryId, mergeSha, targetSha);
        commitPointerMapper.insertCommitParent(repositoryId, mergeSha, sourceSha);

        log.info("Merge commit created: {} ({} + {})", mergeSha, targetSha, sourceSha);
        return Result.ok(mergeSha + " 合并成功。");
    }

    /**
     * 从对象库读取 commit 对象
     */
    private RemoteCommit readCommit(RepositoryPaths paths, String commitSha) {
        File commitFile = new File(paths.getCommits(), commitSha);
        if (!commitFile.exists()) {
            log.error("Commit file not found: {}", commitFile.getAbsolutePath());
            return null;
        }
        return Utils.readObject(commitFile, RemoteCommit.class);
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
     */
    private String saveCommit(RepositoryPaths paths, RemoteCommit commit) {
        // 计算 SHA（基于 commit 内容）
        String sha = Utils.sha1(commit.getMessage(), commit.getParent(),
                commit.getSecondParent(), commit.getTrackedFiles().toString());

        // 写入文件
        File commitFile = new File(paths.getCommits(), sha);
        Utils.writeObject(commitFile, commit);

        return sha;
    }

    /**
     * 查找两个提交的最近公共祖先 (LCA)
     * 通过 BFS 遍历提交图（使用数据库的 commit_parent 表）
     */
    private String findLCA(Long repositoryId, String sourceSha, String targetSha) {
        // 收集 target 的所有祖先
        Set<String> targetAncestors = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(targetSha);

        while (!queue.isEmpty()) {
            String sha = queue.poll();
            if (sha == null || targetAncestors.contains(sha)) {
                continue;
            }
            targetAncestors.add(sha);

            // 查找父提交（从数据库）
            List<String> parents = commitPointerMapper.getParents(repositoryId, sha);
            if (parents != null) {
                queue.addAll(parents);
            }
        }

        // 从 source 开始 BFS，找第一个在 targetAncestors 中的节点
        queue.clear();
        queue.add(sourceSha);
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String sha = queue.poll();
            if (sha == null || visited.contains(sha)) {
                continue;
            }
            visited.add(sha);

            if (targetAncestors.contains(sha)) {
                return sha;
            }

            List<String> parents = commitPointerMapper.getParents(repositoryId, sha);
            if (parents != null) {
                queue.addAll(parents);
            }
        }

        return null;
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

package org.openapitools.service.impl;

import common.Result;
import common.ResultCode;
import dto.PullRequestDTO;
import enums.PRStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.handler.MergeHandler;
import org.openapitools.mapper.*;
import org.openapitools.model.PushEvent;
import org.openapitools.service.PullRequestEventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import po.BranchPointer;
import po.Collaborator;
import po.PullRequest;
import po.Repository;

import java.io.File;
import java.time.LocalDateTime;

@Slf4j
@Service
public class PullRequestEventListenerImpl implements PullRequestEventListener {
    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;
    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Autowired
    private RemoteBranchPointerMapper branchPointerMapper;
    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;
    @Autowired
    private RemoteUserMapper remoteUserMapper;
    @Autowired
    private MergeHandler mergeHandler;


    // 创建PR Webhook
    @Async
    @Override
    @EventListener
    public void onPush(PushEvent pushEvent) {
        try {
            if (pushEvent.isRejected()) {
                // 受保护分支被拒 → 不建 PR，提示用户走 feature 分支
                log.info("分支 {} 受保护，已拒绝推送。请创建 feature 分支并通过 PR 合并",
                        pushEvent.getBranch());
                return;
            }

            // 推送成功 → 检查是否自动建 PR
            Repository repo = remoteRepositoryMapper.selectById(pushEvent.getRepositoryId());
            Collaborator collaborator = remoteCollaboratorMapper.getCollaborator(
                    pushEvent.getUserId(), pushEvent.getRepositoryId());

            // 仓库级开关（null 视为允许，兼容老数据）
            if (repo.getAllowAutoPr() != null && repo.getAllowAutoPr() == 0) {
                log.info("仓库不允许自动 PR，跳过");
                return;
            }

            // 用户级开关（null 视为允许，兼容老数据）
            if (collaborator.getAutoCreatePr() != null && collaborator.getAutoCreatePr() == 0) {
                log.info("用户 {} 关闭了自动 PR，跳过", pushEvent.getUserId());
                return;
            }

            // 两个开关都开 → 自动建 PR
            createPrFromFeatureBranch(pushEvent);
        } catch (Exception e) {
            log.error("PR创建失败，但不影响push结果 : {}", e.getMessage());
        }
    }

    /**
     * 创建 Pull Request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestDTO  (required) 创建 Pull Request 的 DTO
     * @return 创建 Pull Request 的结果
     */
    @Override
    public Result<String> createPullRequest(String owner, String repo, PullRequestDTO pullRequestDTO) {
        log.info("创建 Pull Request {} {} {}", owner, repo, pullRequestDTO);
        PullRequest pullRequest = PullRequest.builder()
                .repositoryId(remoteRepositoryMapper.getRepositoryId(remoteUserMapper.getUserId(owner), repo))
                .title(pullRequestDTO.getTitle())
                .description(pullRequestDTO.getDescription())
                .sourceBranch(pullRequestDTO.getSourceBranch())
                .targetBranch(pullRequestDTO.getTargetBranch())
                .status(PRStatusEnum.OPEN)
                .authorId(remoteUserMapper.getUserId(owner))
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();
        remotePullRequestMapper.insert(pullRequest);
        log.info("创建 Pull Request 成功 {}", pullRequest.getId());
        return Result.ok();
    }

    /**
     * 合并 Pull Request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 合并 Pull Request 的结果
     */
    @Override
    public Result<String> mergePullRequest(String owner, String repo, String pullRequestId) {
        PullRequest pullRequest = remotePullRequestMapper.selectById(pullRequestId);
        if (pullRequest == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        
        log.info("合并 Pull Request {}", pullRequest.getId());
        
        // 传递 PR ID，让 MergeHandler 检查暂存区
        Result<String> merge = mergeHandler.merge(pullRequest.getRepositoryId(),
                pullRequest.getSourceBranch(), pullRequest.getTargetBranch(), 
                pullRequest.getId());
        
        if (merge.getCode() == ResultCode.OK) {
            // 合并成功：清理暂存区，更新 PR 状态
            cleanupStaging(pullRequest.getId());
            updatePRStatus(pullRequest, PRStatusEnum.MERGED);
        } else if (merge.getCode() == ResultCode.MERGE_CONFLICT) {
            // data 含 "冲突目录|tracked文件" 两个路径时（首次生成暂存区）才写库；
            // 若从暂存区恢复但仍有冲突标记，data 为 null，保持原路径不变，避免 NPE
            String data = merge.getData();
            if (data != null && data.contains("|")) {
                String[] parts = data.split("\\|");
                pullRequest.setStagingPath(parts[0]);
                pullRequest.setStagingPathTracked(parts[1]);
                pullRequest.setUpdateTime(LocalDateTime.now());
                remotePullRequestMapper.updateById(pullRequest);
                log.info("PR {} 合并冲突，暂存区路径已保存：{}", pullRequest.getId(), parts[0]);
            }
        }
        
        return merge;
    }

    /**
     * 更新 PR 状态
     */
    private void updatePRStatus(PullRequest pr, PRStatusEnum status) {
        pr.setStatus(status);
        pr.setUpdateTime(LocalDateTime.now());
        remotePullRequestMapper.updateById(pr);
    }

    /**
     * 清理暂存区（合并成功或 PR 关闭时调用）
     */
    private void cleanupStaging(Long prId) {
        PullRequest pr = remotePullRequestMapper.selectById(prId);
        if (pr == null) {
            return;
        }
        // 删除冲突文件目录
        if (pr.getStagingPath() != null) {
            File stagingDir = new File(pr.getStagingPath());
            if (stagingDir.exists()) {
                deleteDirectory(stagingDir);
                log.info("清理暂存区：{}", pr.getStagingPath());
            }
        }
        // 删除序列化的 trackedFiles 文件
        if (pr.getStagingPathTracked() != null) {
            File trackedFile = new File(pr.getStagingPathTracked());
            if (trackedFile.exists()) {
                trackedFile.delete();
                log.info("清理 trackedFiles 文件：{}", pr.getStagingPathTracked());
            }
        }
        // 清空 PR 表的两个暂存区路径
        pr.setStagingPath(null);
        pr.setStagingPathTracked(null);
        pr.setUpdateTime(LocalDateTime.now());
        remotePullRequestMapper.updateById(pr);
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 从 feature 分支推送中创建 PR
     * 分支已存在，直接建 PR
     * @param pushEvent 推送事件
     */
    private void createPrFromFeatureBranch(PushEvent pushEvent) {
        log.info("从 feature 分支推送中创建 PR {}", pushEvent.getBranch());
        createPullRequestObject(pushEvent, pushEvent.getBranch());
    }

    /**
     * 创建 PR 对象
     * @param pushEvent 推送事件
     * @param sourceBranch 源分支名
     */
    private void createPullRequestObject(PushEvent pushEvent, String sourceBranch) {
        Repository repository = remoteRepositoryMapper.selectById(pushEvent.getRepositoryId());
        PullRequest pr = PullRequest.builder()
                .repositoryId(pushEvent.getRepositoryId())
                .title("Update " + sourceBranch)
                .description("Auto-created on push to " + sourceBranch)
                .sourceBranch(sourceBranch)
                .targetBranch(repository.getDefaultBranch())
                .status(PRStatusEnum.OPEN)  // OPEN
                .authorId(pushEvent.getUserId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();
        remotePullRequestMapper.insert(pr);
    }

    /**
     * 创建分支
     * @param repositoryId 仓库 ID
     * @param tempBranch 临时分支名
     * @param sha1 提交 SHA1
     */
    private void createBranch(Long repositoryId, String tempBranch, String sha1) {
        log.info("创建分支 {} 到 {}", tempBranch, sha1);
        BranchPointer branchPointer = BranchPointer.builder()
                .repositoryId(repositoryId)
                .branchName(tempBranch)
                .commitSha(sha1)
                .isDefault(0)
                .version(0)
                .updateTime(LocalDateTime.now())
                .isForcePush(0)
                .isProtected(0)
                .isDeleted(0)
                .build();
        branchPointerMapper.insert(branchPointer);
    }
}

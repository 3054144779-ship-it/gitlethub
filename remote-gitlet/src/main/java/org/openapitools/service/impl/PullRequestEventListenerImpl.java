package org.openapitools.service.impl;

import common.Result;
import dto.PullRequestDTO;
import lombok.extern.slf4j.Slf4j;
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

            // 仓库级开关
            if (repo.getAllowAutoPr() == 0) {
                log.info("仓库不允许自动 PR，跳过");
                return;
            }

            // 用户级开关
            if (collaborator.getAutoCreatePr() == 0) {
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
     * 从被拒绝的推送中创建 PR
     * 被拒的 commit 没有分支承载，需要先建临时分支
     * @param pushEvent 推送事件
     */
    private void createPrFromRejectedPush(PushEvent pushEvent) {
        // 把被拒的 commit 存到临时分支
        String tempBranch = "pr/" + pushEvent.getUserId() + "-" + System.currentTimeMillis();
        createBranch(pushEvent.getRepositoryId(), tempBranch, pushEvent.getSha1());
        log.info("从被拒绝的推送中创建 PR，临时分支: {}", tempBranch);
        createPullRequestObject(pushEvent, tempBranch);
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
                .status(0)  // OPEN
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

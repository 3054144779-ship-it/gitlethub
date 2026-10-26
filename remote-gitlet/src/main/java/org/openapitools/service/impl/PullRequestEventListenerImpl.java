package org.openapitools.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import common.Result;
import common.ResultCode;
import dto.PullRequestDTO;
import enums.PRStatusEnum;
import enums.PrReviewStateEnum;
import gitlet.commit.Commit;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.handler.MergeHandler;
import org.openapitools.mapper.*;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.*;
import org.openapitools.service.PullRequestEventListener;
import org.openapitools.service.support.RepositoryObjectReader;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import po.BranchPointer;
import po.Collaborator;
import po.PrReview;
import po.PullRequest;
import po.Repository;
import po.User;
import vo.PullRequestVO;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.openapitools.utils.Utils.join;

@Slf4j
@Service
public class PullRequestEventListenerImpl implements PullRequestEventListener {
    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;
    @Autowired
    private RemotePrReviewMapper remotePrReviewMapper;
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
    @Autowired
    private RepositoryObjectReader reader;
    @Autowired
    private RabbitTemplate rabbitTemplate;


    // 创建PR
    @Async
    @Override
    @EventListener
    public void onPush(PushEvent pushEvent) {
        try {
            log.info("接收到 Push 事件 {}", pushEvent.isRejected() ? "保护分支" : "非保护分支");
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
                log.info("仓库不允许自动 PR，跳过，仓库的Owner除外");
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
     * @param actorId  (optional) 调用者用户 ID（由鉴权过滤器注入）
     * @return 创建 Pull Request 的结果
     */
    @Override
    public Result<String> createPullRequest(String owner, String repo, PullRequestDTO pullRequestDTO, Long actorId) {
        log.info("创建 Pull Request {} {} {} by {}", owner, repo, pullRequestDTO, actorId);
        Long ownerId = remoteUserMapper.getUserId(owner);
        // 作者是发起人；未带身份时（如未登录/CLI）回退为仓库所有者，保持旧行为
        Long authorId = actorId != null ? actorId : ownerId;
        if (actorId == null) {
            log.warn("创建 Pull Request 未携带调用者身份，作者回退为仓库所有者 {}", ownerId);
        }
        PullRequest pullRequest = PullRequest.builder()
                .repositoryId(remoteRepositoryMapper.getRepositoryId(ownerId, repo))
                .title(pullRequestDTO.getTitle())
                .description(pullRequestDTO.getDescription())
                .sourceBranch(pullRequestDTO.getSourceBranch())
                .targetBranch(pullRequestDTO.getTargetBranch())
                .status(PRStatusEnum.OPEN)
                .authorId(authorId)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();
        remotePullRequestMapper.insert(pullRequest);
        log.info("创建 Pull Request 成功 {}", pullRequest.getId());
        return Result.ok();
    }

    /**
     * 合并 Pull Request：仅仓库 OWNER 可操作，协作者只能提 PR
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @param actorId  (optional) 调用者用户 ID；为空视为无权
     * @return 合并 Pull Request 的结果
     */
    @Override
    public Result<String> mergePullRequest(String owner, String repo, String pullRequestId, Long actorId) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        // 限定 PR 必须属于 URL 上的仓库，与 getPullRequestById/closePullRequest 保持一致
        PullRequest pullRequest = findPullRequest(repositoryId, pullRequestId);
        if (pullRequest == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }

        Result<String> permission = checkOwner(repositoryId, actorId);
        if (permission != null) {
            return permission;
        }

        log.info("合并 Pull Request {} by user {}", pullRequest.getId(), actorId);

        // 合并前先记录目标分支基线 commit：合并会推进 master 指针，
        // 若合并后再读 master，diff 会因两边树一致而全部变成 UNCHANGED
        BranchPointer targetBasePointer = branchPointerMapper
                .getBranchPointer(repositoryId, pullRequest.getTargetBranch());
        // 保护分支要求评审通过：取每人最新一条评审判定。
        // OWNER 自建的 PR 跳过门禁：OWNER 本就可绕过保护分支直推，再要求他人批「通过」会导致单人多仓库下无法合并。
        boolean ownerAuthored = isOwner(repositoryId, pullRequest.getAuthorId());
        if (targetBasePointer != null && Integer.valueOf(1).equals(targetBasePointer.getIsProtected())
                && !ownerAuthored) {
            Result<String> gate = checkReviewGate(repositoryId, pullRequest.getId());
            if (gate != null) {
                return gate;
            }
        }
        String targetBaseSha = targetBasePointer == null ? null : targetBasePointer.getCommitSha();

        // 传递 PR ID，让 MergeHandler 检查暂存区
        Result<String> merge = mergeHandler.merge(pullRequest.getRepositoryId(),
                pullRequest.getSourceBranch(), pullRequest.getTargetBranch(), 
                pullRequest.getId());
        
        if (merge.getCode() == ResultCode.OK) {
            // 合并成功：清理暂存区，更新 PR 状态
            cleanupStaging(pullRequest.getId());
            updatePRStatus(pullRequest, PRStatusEnum.MERGED);
            // 构建消息并发送MQ
            sendMessage(owner, repo, pullRequest, targetBaseSha);
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
     * 获取仓库最近的一条 Pull Request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 获取 Pull Request 的结果
     */
    @Override
    public Result<PullRequestVO> getPullRequest(String owner, String repo) {
        log.info("获取 Pull Request {} {}", owner, repo);
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        // 修复：原实现是 selectById(repositoryId)，把仓库ID当成PR主键用了
        PullRequest pullRequest = remotePullRequestMapper.selectOne(
                Wrappers.<PullRequest>lambdaQuery()
                        .eq(PullRequest::getRepositoryId, repositoryId)
                        .orderByDesc(PullRequest::getCreateTime)
                        .last("LIMIT 1"));
        log.info("获取 Pull Request 成功 {}", pullRequest);
        return Result.ok(toVO(pullRequest));
    }

    /**
     * 按 id 获取 Pull Request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 获取 Pull Request 的结果
     */
    @Override
    public Result<PullRequestVO> getPullRequestById(String owner, String repo, String pullRequestId) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        PullRequest pullRequest = findPullRequest(repositoryId, pullRequestId);
        if (pullRequest == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        return Result.ok(toVO(pullRequest));
    }

    /**
     * 列出仓库的 Pull Request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param status  (optional) 状态：0 open / 1 closed / 2 merged；空表示全部
     * @param page  (optional) 页码，从 1 开始，缺省 1
     * @param size  (optional) 每页条数，缺省 20
     * @return PR 列表
     */
    @Override
    public Result<List<PullRequestVO>> listPullRequests(String owner, String repo,
                                                        Integer status, Integer page, Integer size) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        PRStatusEnum statusEnum = toStatus(status);
        if (status != null && statusEnum == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 状态非法");
        }
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : size;
        Page<PullRequest> result = remotePullRequestMapper.selectPage(
                new Page<>(p, s),
                Wrappers.<PullRequest>lambdaQuery()
                        .eq(PullRequest::getRepositoryId, repositoryId)
                        .eq(statusEnum != null, PullRequest::getStatus, statusEnum)
                        .orderByDesc(PullRequest::getCreateTime));
        List<PullRequestVO> list = new ArrayList<>();
        for (PullRequest pr : result.getRecords()) {
            list.add(toVO(pr));
        }
        return Result.ok(list);
    }

    /**
     * 关闭 Pull Request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 关闭结果
     */
    @Override
    public Result<String> closePullRequest(String owner, String repo, String pullRequestId, Long actorId) {
        log.info("关闭 Pull Request {} by user {}", pullRequestId, actorId);
        if (actorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        PullRequest pullRequest = findPullRequest(repositoryId, pullRequestId);
        if (pullRequest == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        // PR 作者或仓库 OWNER 可以关闭；其他人一律拒绝
        if (!actorId.equals(pullRequest.getAuthorId()) && checkOwner(repositoryId, actorId) != null) {
            return Result.fail(ResultCode.FORBIDDEN, "只有 PR 作者或仓库所有者可以关闭该 PR");
        }
        if (pullRequest.getStatus() != PRStatusEnum.OPEN) {
            return Result.fail(ResultCode.BAD_REQUEST, "只有 open 状态的 PR 可以关闭");
        }
        cleanupStaging(pullRequest.getId());
        updatePRStatus(pullRequest, PRStatusEnum.CLOSED);
        return Result.ok();
    }

    /**
     * 发送消息到MQ
     * @param targetBaseSha 合并前目标分支的基线 commit SHA，用于计算真实的 diff
     */
    private void sendMessage(String owner, String repo, PullRequest pullRequest, String targetBaseSha) {
        RepositoryPaths repositoryPaths = reader.resolvePaths(owner, repo);

        Long userId = remoteUserMapper.getUserId(owner);
        Long repositoryId = pullRequest.getRepositoryId();
        String branchName = pullRequest.getTargetBranch();
        // 获取PR创建者的提交信息
        User author = remoteUserMapper.selectById(pullRequest.getAuthorId());
        BranchPointer sourceBranchPointer = branchPointerMapper
                .getBranchPointer(repositoryId, pullRequest.getSourceBranch());

        // 解析Commit
        String sourceCommitSha = sourceBranchPointer.getCommitSha();

        Commit sourceCommit = reader.readCommitObject(repositoryPaths, sourceCommitSha);
        // 使用合并前的基线，而不是合并后已推进的 master 指针
        Commit targetCommit = reader.readCommitObject(repositoryPaths, targetBaseSha);

        List<TrackedFile> trackedFiles = sourceCommit.getTrackedFiles().keySet()
                .stream()
                .map(trackedFile -> TrackedFile.builder()
                        .filePath(join(repositoryPaths.getObject(), sourceCommit.getTrackedFiles()
                                .get(trackedFile)).toString())
                        .status(getState(trackedFile, sourceCommit.getTrackedFiles(), targetCommit.getTrackedFiles()))
                        .language(resolveLanguage(trackedFile))
                        .build())
                .collect(Collectors.toList());

        CommitInfo commitInfo = CommitInfo.builder()
                .commitSha(sourceCommitSha)
                .message(sourceCommit.getMessage())
                .time(sourceCommit.getTime())
                .mergeFiles(sourceCommit.getMergeFiles())
                .trackedFiles(trackedFiles)
                .build();

        PrInfo prInfo = PrInfo.builder()
                .prName(pullRequest.getTitle() + "\n" + pullRequest.getDescription()) // 这里用名字+描述
                .author(author.getUsername())
                .commits(List.of(commitInfo))
                .build();

        MessageQueueRequest mq = MessageQueueRequest.builder()
                .userId(userId)
                .repositoryId(repositoryId)
                .branchName(branchName)
                .prInfo(prInfo)
                .build();

        log.info("发送消息到MQ：{}", mq);
        rabbitTemplate.convertAndSend("gitlet.pr.exchange", "pr.merged", mq);
    }

    private String getState(String trackedFile,
                            Map<String, String> sourceTrackedFiles, Map<String, String> targetTrackedFiles) {
        String fileSha1 = sourceTrackedFiles.get(trackedFile);
        String fileSha2 = targetTrackedFiles.get(trackedFile);

        String state = "UNKNOWN_OPERATION";

        if (fileSha1 != null && fileSha2 == null) {
            // 源分支有、目标基线没有 => 本次 PR 新增
            state = "ADDED";
        } else if (fileSha1 == null && fileSha2 != null) {
            // 源分支没有、目标基线有 => 本次 PR 删除
            state = "DELETED";
        } else if (fileSha1 != null && fileSha2 != null) {
            if (fileSha1.equals(fileSha2)) {
                // sha1相同说明没变化
                state = "UNCHANGED";
            } else {
                state = "MODIFIED";
            }
        }
        return state;
    }

    /** owner/repo -> repositoryId；任一环节缺失返回 null */
    private Long resolveRepositoryId(String owner, String repo) {
        Long userId = remoteUserMapper.getUserId(owner);
        if (userId == null) {
            return null;
        }
        return remoteRepositoryMapper.getRepositoryId(userId, repo);
    }

    /**
     * 校验调用者是否为仓库 OWNER（collaborator 表 level=OWNER 的行）。
     * 通过返回 null，否则返回失败结果；无身份同样视为无权。
     */
    private Result<String> checkOwner(Long repositoryId, Long actorId) {
        if (actorId == null) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.ONLY_OWNER_CAN_MERGE);
        }
        Collaborator self = remoteCollaboratorMapper.getCollaborator(actorId, repositoryId);
        boolean deleted = self != null && self.getIsDeleted() != null && self.getIsDeleted() == 1;
        if (self == null || deleted || self.getLevel() == null || !self.getLevel().isOwner()) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.ONLY_OWNER_CAN_MERGE);
        }
        return null;
    }

    /** 用户是否为该仓库的 OWNER（用于 OWNER 自建 PR 的评审门禁豁免） */
    private boolean isOwner(Long repositoryId, Long userId) {
        if (repositoryId == null || userId == null) {
            return false;
        }
        Collaborator self = remoteCollaboratorMapper.getCollaborator(userId, repositoryId);
        boolean deleted = self != null && self.getIsDeleted() != null && self.getIsDeleted() == 1;
        return self != null && !deleted && self.getLevel() != null && self.getLevel().isOwner();
    }

    /**
     * 保护分支合并门禁：同一评审人取最新一条，
     * 存在 CHANGES_REQUESTED 即拒绝，至少一条 APPROVED 才放行。
     */
    private Result<String> checkReviewGate(Long repositoryId, Long prId) {
        List<PrReview> reviews = remotePrReviewMapper.selectList(
                Wrappers.<PrReview>lambdaQuery()
                        .eq(PrReview::getRepositoryId, repositoryId)
                        .eq(PrReview::getPullRequestId, prId)
                        .orderByAsc(PrReview::getCreateTime)
                        .orderByAsc(PrReview::getId));
        Map<Long, PrReview> latestByReviewer = new HashMap<>();
        for (PrReview review : reviews) {
            latestByReviewer.put(review.getReviewerId(), review);
        }
        boolean changesRequested = false;
        boolean approved = false;
        for (PrReview latest : latestByReviewer.values()) {
            if (latest.getState() == PrReviewStateEnum.CHANGES_REQUESTED) {
                changesRequested = true;
            } else if (latest.getState() == PrReviewStateEnum.APPROVED) {
                approved = true;
            }
        }
        if (changesRequested) {
            return Result.fail(ResultCode.REVIEW_REJECTED, MsgConstant.PR_REVIEW_REJECTED);
        }
        if (!approved) {
            return Result.fail(ResultCode.REVIEW_APPROVAL_REQUIRED, MsgConstant.PR_APPROVAL_REQUIRED);
        }
        return null;
    }

    /** 按 PR id 查询，且必须属于该仓库（避免跨仓库读别人的 PR） */
    private PullRequest findPullRequest(Long repositoryId, String pullRequestId) {
        if (pullRequestId == null || pullRequestId.isBlank()) {
            return null;
        }
        PullRequest pullRequest;
        try {
            pullRequest = remotePullRequestMapper.selectById(Long.valueOf(pullRequestId));
        } catch (NumberFormatException e) {
            return null;
        }
        if (pullRequest == null || !repositoryId.equals(pullRequest.getRepositoryId())) {
            return null;
        }
        return pullRequest;
    }

    /** status code -> 枚举；null 表示不过滤，未知 code 返回 null */
    private PRStatusEnum toStatus(Integer status) {
        if (status == null) {
            return null;
        }
        for (PRStatusEnum value : PRStatusEnum.values()) {
            if (value.getValue().equals(status)) {
                return value;
            }
        }
        return null;
    }

    /** PO -> VO，补齐作者名（PO 只有 authorId） */
    private PullRequestVO toVO(PullRequest pullRequest) {
        if (pullRequest == null) {
            return null;
        }
        PullRequestVO vo = BeanUtil.copyProperties(pullRequest, PullRequestVO.class);
        if (pullRequest.getAuthorId() != null) {
            User author = remoteUserMapper.selectById(pullRequest.getAuthorId());
            vo.setAuthorName(author == null ? null : author.getUsername());
        }
        return vo;
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
        File tempParentDir = null;
        // 删除冲突文件目录
        if (pr.getStagingPath() != null) {
            File stagingDir = new File(pr.getStagingPath());
            if (stagingDir.exists()) {
                deleteDirectory(stagingDir);
                tempParentDir = stagingDir.getParentFile();
                log.info("清理暂存区：{}", pr.getStagingPath());
            }
        }
        // 删除序列化的 trackedFiles 文件
        if (pr.getStagingPathTracked() != null) {
            File trackedFile = new File(pr.getStagingPathTracked());
            if (trackedFile.exists()) {
                trackedFile.delete();
                if (tempParentDir == null) {
                    tempParentDir = trackedFile.getParentFile();
                }
                log.info("清理 trackedFiles 文件：{}", pr.getStagingPathTracked());
            }
        }
        // 删除父级 temp 目录：File.delete() 仅在目录为空时才会成功，
        // 因此不会影响同一仓库下其他并发 PR 仍在使用中的临时子目录
        if (tempParentDir != null && tempParentDir.isDirectory()) {
            String[] remaining = tempParentDir.list();
            if (remaining == null || remaining.length == 0) {
                tempParentDir.delete();
                log.info("清理临时父目录：{}", tempParentDir.getAbsolutePath());
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
        // TODO: 后续接入ai，根据提交内容生成 PR 标题和描述
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

    /**
     * 根据文件扩展名推断编程语言。
     * 返回 null 表示无对应语言（如 markdown / word / txt 等非代码文件），此时不填充 language。
     */
    private String resolveLanguage(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        // 先去掉目录部分，避免 Windows 路径中的目录名含 '.' 造成误判
        String fileName = filePath.replace('\\', '/');
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase();
        return EXTENSION_LANGUAGE_MAP.get(ext);
    }

    private static final Map<String, String> EXTENSION_LANGUAGE_MAP = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("py", "python"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"),
            Map.entry("go", "go"),
            Map.entry("rs", "rust"),
            Map.entry("c", "c"),
            Map.entry("h", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("cxx", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("hxx", "cpp"),
            Map.entry("hh", "cpp"),
            Map.entry("rb", "ruby"),
            Map.entry("php", "php"),
            Map.entry("cs", "c_sharp"),
            Map.entry("html", "html"),
            Map.entry("css", "css"),
            Map.entry("json", "json")
    );

}

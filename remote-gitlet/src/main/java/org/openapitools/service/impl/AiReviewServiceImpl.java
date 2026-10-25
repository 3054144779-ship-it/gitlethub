package org.openapitools.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import common.Result;
import common.ResultCode;
import dto.AiReviewCreateDTO;
import dto.DiffData;
import enums.AiReviewStatusEnum;
import org.openapitools.handler.DiffBuilder;
import org.openapitools.mapper.RemoteAiReviewRecordMapper;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.AiReviewService;
import org.openapitools.service.CommitQueryService;
import org.openapitools.service.support.AiReviewWorker;
import org.openapitools.service.support.RepositoryObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import po.AiReviewRecord;
import po.BranchPointer;
import po.Collaborator;
import po.PullRequest;
import vo.AiReviewRecordVO;
import vo.FileChangeVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiReviewServiceImpl implements AiReviewService {

    @Autowired
    private RemoteAiReviewRecordMapper remoteAiReviewRecordMapper;
    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;
    @Autowired
    private RemoteBranchPointerMapper remoteBranchPointerMapper;
    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;
    @Autowired
    private RemoteUserMapper remoteUserMapper;
    @Autowired
    private RepositoryObjectReader repositoryObjectReader;
    @Autowired
    private DiffBuilder diffBuilder;
    @Autowired
    private CommitQueryService commitQueryService;
    @Autowired
    private AiReviewWorker aiReviewWorker;

    @Value("${ai.review.model:gitlet-ai}")
    private String reviewModel;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<AiReviewRecordVO> createReview(String owner, String repo,
                                                 AiReviewCreateDTO aiReviewCreateDTO, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        if (aiReviewCreateDTO == null
                || (aiReviewCreateDTO.getPullRequestId() == null && StrUtil.isBlank(aiReviewCreateDTO.getCommitSha()))
                || (aiReviewCreateDTO.getPullRequestId() != null && StrUtil.isNotBlank(aiReviewCreateDTO.getCommitSha()))) {
            return Result.fail(ResultCode.BAD_REQUEST, "pullRequestId 与 commitSha 必须二选一");
        }
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkMember(repositoryId, userId);
        if (permission != null) {
            return Result.fail(permission.getCode(), permission.getMessage());
        }

        Long prId = null;
        String prName = null;
        String commitSha;
        List<DiffData> diffData;

        if (aiReviewCreateDTO.getPullRequestId() != null) {
            PullRequest pullRequest = remotePullRequestMapper.selectById(aiReviewCreateDTO.getPullRequestId());
            if (pullRequest == null || !repositoryId.equals(pullRequest.getRepositoryId())) {
                return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
            }
            BranchPointer source = remoteBranchPointerMapper
                    .getBranchPointer(repositoryId, pullRequest.getSourceBranch());
            if (source == null || StrUtil.isBlank(source.getCommitSha())) {
                return Result.fail(ResultCode.BRANCH_NOT_FOUND, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
            }
            prId = pullRequest.getId();
            prName = pullRequest.getTitle();
            commitSha = source.getCommitSha();
            diffData = diffBuilder.buildPrDiff(repositoryId,
                    pullRequest.getSourceBranch(), pullRequest.getTargetBranch());
        } else {
            commitSha = aiReviewCreateDTO.getCommitSha().trim();
            if (repositoryObjectReader.readCommitObject(
                    repositoryObjectReader.resolvePaths(owner, repo), commitSha) == null) {
                return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
            }
            Result<List<FileChangeVO>> changes = commitQueryService.diffCommit(owner, repo, commitSha);
            if (changes.getCode() == null || changes.getCode() != ResultCode.OK) {
                return Result.fail(changes.getCode(), changes.getMessage());
            }
            diffData = toDiffData(changes.getData());
        }

        if (diffData == null || diffData.isEmpty()) {
            return Result.fail(ResultCode.BAD_REQUEST, "没有可审查的变更");
        }

        String diffSha256 = hashDiff(diffData);
        LocalDateTime now = LocalDateTime.now();

        // 同一仓库内相同 diff 已有成功结论时直接复用，避免重复消耗模型额度
        AiReviewRecord reusable = remoteAiReviewRecordMapper.selectOne(
                Wrappers.<AiReviewRecord>lambdaQuery()
                        .eq(AiReviewRecord::getRepositoryId, repositoryId)
                        .eq(AiReviewRecord::getDiffSha256, diffSha256)
                        .eq(AiReviewRecord::getStatus, AiReviewStatusEnum.SUCCESS)
                        .orderByDesc(AiReviewRecord::getCreateTime)
                        .last("LIMIT 1"));
        if (reusable != null) {
            AiReviewRecord record = AiReviewRecord.builder()
                    .repositoryId(repositoryId)
                    .pullRequestId(prId)
                    .commitSha(commitSha)
                    .diffSha256(diffSha256)
                    .model(reusable.getModel())
                    .status(AiReviewStatusEnum.SUCCESS)
                    .conclusion(reusable.getConclusion())
                    .durationMs(0)
                    .triggerUserId(userId)
                    .createTime(now)
                    .updateTime(now)
                    .isDeleted(0)
                    .build();
            remoteAiReviewRecordMapper.insert(record);
            return Result.ok(toVO(record));
        }

        AiReviewRecord record = AiReviewRecord.builder()
                .repositoryId(repositoryId)
                .pullRequestId(prId)
                .commitSha(commitSha)
                .diffSha256(diffSha256)
                .model(reviewModel)
                .status(AiReviewStatusEnum.RUNNING)
                .triggerUserId(userId)
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
        remoteAiReviewRecordMapper.insert(record);

        aiReviewWorker.run(record.getId(), remoteUserMapper.getUserId(owner), repositoryId,
                prId, prName, commitSha, diffData);
        return Result.ok(toVO(record));
    }

    @Override
    public Result<List<AiReviewRecordVO>> listReviews(String owner, String repo, Long pullRequestId,
                                                      String commitSha, Integer page, Integer size) {
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : size;
        Page<AiReviewRecord> result = remoteAiReviewRecordMapper.selectPage(
                new Page<>(p, s),
                Wrappers.<AiReviewRecord>lambdaQuery()
                        .eq(AiReviewRecord::getRepositoryId, repositoryId)
                        .eq(pullRequestId != null, AiReviewRecord::getPullRequestId, pullRequestId)
                        .eq(StrUtil.isNotBlank(commitSha), AiReviewRecord::getCommitSha, commitSha)
                        .orderByDesc(AiReviewRecord::getCreateTime)
                        .orderByDesc(AiReviewRecord::getId));
        List<AiReviewRecordVO> list = new ArrayList<>(result.getRecords().size());
        for (AiReviewRecord record : result.getRecords()) {
            list.add(toVO(record));
        }
        return Result.ok(list);
    }

    @Override
    public Result<AiReviewRecordVO> getReview(String owner, String repo, Long recordId) {
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        AiReviewRecord record = recordId == null ? null : remoteAiReviewRecordMapper.selectById(recordId);
        if (record == null || !repositoryId.equals(record.getRepositoryId())) {
            return Result.fail(ResultCode.BAD_REQUEST, "审查记录不存在");
        }
        return Result.ok(toVO(record));
    }

    /** OWNER / CONTRIBUTOR 才允许发起审查；通过返回 null */
    private Result<String> checkMember(Long repositoryId, Long userId) {
        Collaborator self = remoteCollaboratorMapper.getCollaborator(userId, repositoryId);
        boolean deleted = self != null && self.getIsDeleted() != null && self.getIsDeleted() == 1;
        if (self == null || deleted || self.getLevel() == null || self.getLevel().isGuest()) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.ONLY_MEMBERS_CAN_CONTRIBUTE);
        }
        return null;
    }

    private List<DiffData> toDiffData(List<FileChangeVO> changes) {
        List<DiffData> result = new ArrayList<>();
        if (changes == null) {
            return result;
        }
        for (FileChangeVO change : changes) {
            result.add(DiffData.builder()
                    .file_path(change.getPath())
                    .old_content(change.getOldContent() == null ? "" : change.getOldContent())
                    .new_content(change.getNewContent() == null ? "" : change.getNewContent())
                    .build());
        }
        return result;
    }

    private String hashDiff(List<DiffData> diffData) {
        StringBuilder sb = new StringBuilder();
        for (DiffData data : diffData) {
            sb.append(data.getFile_path()).append('\n')
                    .append(data.getDiff() == null ? "" : data.getDiff()).append('\n')
                    .append(data.getOld_content() == null ? "" : data.getOld_content()).append('\n')
                    .append(data.getNew_content() == null ? "" : data.getNew_content()).append('\n');
        }
        return SecureUtil.sha256(sb.toString());
    }

    private AiReviewRecordVO toVO(AiReviewRecord record) {
        return AiReviewRecordVO.builder()
                .id(record.getId())
                .pullRequestId(record.getPullRequestId())
                .commitSha(record.getCommitSha())
                .diffSha256(record.getDiffSha256())
                .model(record.getModel())
                .status(record.getStatus())
                .conclusion(record.getConclusion())
                .errorMessage(record.getErrorMessage())
                .durationMs(record.getDurationMs())
                .triggerUserId(record.getTriggerUserId())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}

package org.openapitools.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import common.Result;
import common.ResultCode;
import dto.PrCommentCreateDTO;
import dto.PrCommentUpdateDTO;
import dto.PrReviewCreateDTO;
import enums.PrCommentTypeEnum;
import enums.PrReviewStateEnum;
import org.openapitools.mapper.*;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.SocialService;
import org.openapitools.service.support.RepositoryObjectReader;
import org.openapitools.utils.UserNameCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import po.Collaborator;
import po.PrComment;
import po.PrReview;
import po.PullRequest;
import vo.PrCommentVO;
import vo.PrReviewVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SocialServiceImpl implements SocialService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Autowired
    private RemotePrCommentMapper remotePrCommentMapper;
    @Autowired
    private UserNameCache userNameCache;
    @Autowired
    private RepositoryObjectReader repositoryObjectReader;
    @Autowired
    private RemotePrReviewMapper remotePrReviewMapper;
    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;
    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;


    @Override
    public Result<IPage<PrCommentVO>> getComments(
            String owner,
            String repo,
            Long prId,
            Integer commentType,
            Integer pageNum,
            Integer pageSize
    ) {
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        if (findPr(repositoryId, prId) == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }

        PrCommentTypeEnum type = null;
        if (commentType != null) {
            type = PrCommentTypeEnum.fromValue(commentType);
            if (type == null) {
                return Result.fail(ResultCode.BAD_REQUEST, "评论类型非法");
            }
        }

        int p = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int s = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;

        // 只对顶级评论分页：一条热门评论的回复不能被拆到别的页，否则前端树拼不出来
        Page<PrComment> page = remotePrCommentMapper.selectPage(
                new Page<>(p, s),
                Wrappers.<PrComment>lambdaQuery()
                        .eq(PrComment::getRepositoryId, repositoryId)
                        .eq(PrComment::getPullRequestId, prId)
                        .eq(type != null, PrComment::getCommentType, type)
                        .isNull(PrComment::getReplyToId)
                        .orderByAsc(PrComment::getCreateTime)
                        .orderByAsc(PrComment::getId));

        // 回复一次性捞全，不受分页影响；用 map 按父 id 归组后递归挂载
        Map<Long, List<PrComment>> repliesByParent = loadReplies(repositoryId, prId);
        Map<Long, String> usernameCache = new HashMap<>();
        IPage<PrCommentVO> voPage = page.convert(comment -> toVO(comment, usernameCache));
        for (PrCommentVO parent : voPage.getRecords()) {
            attachReplies(parent, repliesByParent, usernameCache);
        }
        return Result.ok(voPage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> createComment(String owner, String repo, Long prId,
                                        PrCommentCreateDTO prCommentCreateDTO, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        if (prCommentCreateDTO == null || StrUtil.isBlank(prCommentCreateDTO.getBody())) {
            return Result.fail(ResultCode.BAD_REQUEST, "评论内容不能为空");
        }

        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        if (findPr(repositoryId, prId) == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        Result<String> member = checkMember(repositoryId, userId);
        if (member != null) {
            return member;
        }

        PrCommentTypeEnum commentType = prCommentCreateDTO.getCommentType() == null
                ? PrCommentTypeEnum.GENERAL
                : PrCommentTypeEnum.fromValue(prCommentCreateDTO.getCommentType());
        if (commentType == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "评论类型非法");
        }

        PrComment parent = null;
        if (prCommentCreateDTO.getReplyToId() != null) {
            parent = remotePrCommentMapper.selectOne(Wrappers.<PrComment>lambdaQuery()
                    .eq(PrComment::getId, prCommentCreateDTO.getReplyToId())
                    .eq(PrComment::getRepositoryId, repositoryId)
                    .eq(PrComment::getPullRequestId, prId));
            if (parent == null) {
                return Result.fail(ResultCode.BAD_REQUEST, "回复的评论不存在");
            }
            commentType = parent.getCommentType();
        }

        PrComment prComment = new PrComment();
        prComment.setRepositoryId(repositoryId);
        prComment.setPullRequestId(prId);
        prComment.setUserId(userId);
        prComment.setBody(prCommentCreateDTO.getBody().trim());
        prComment.setCommentType(commentType);
        prComment.setReplyToId(parent == null ? null : parent.getId());
        // 回复落在父评论所属的评审线程里；独立评论不挂评审
        prComment.setReviewId(parent == null ? null : parent.getReviewId());

        if (commentType == PrCommentTypeEnum.INLINE) {
            // 回复必须沿用父评论的定位，否则前端 diff 视图挂不上这一行
            String filePath = parent != null ? parent.getFilePath() : prCommentCreateDTO.getFilePath();
            Integer lineNumber = parent != null ? parent.getLineNumber() : prCommentCreateDTO.getLineNumber();
            Integer diffSide = parent != null ? parent.getDiffSide() : prCommentCreateDTO.getDiffSide();
            String commitSha = parent != null ? parent.getCommitSha() : prCommentCreateDTO.getCommitSha();

            if (StrUtil.isBlank(filePath) || lineNumber == null || diffSide == null) {
                return Result.fail(ResultCode.BAD_REQUEST, "行级评论必须提供 file_path、line_number、diff_side");
            }
            if (diffSide != 0 && diffSide != 1) {
                return Result.fail(ResultCode.BAD_REQUEST, "diff_side 只能是 0（旧行）或 1（新行）");
            }
            prComment.setFilePath(filePath);
            prComment.setLineNumber(lineNumber);
            prComment.setDiffSide(diffSide);
            prComment.setCommitSha(commitSha);
        }

        LocalDateTime now = LocalDateTime.now();
        prComment.setCreateTime(now);
        prComment.setUpdateTime(now);
        prComment.setIsDeleted(0);

        remotePrCommentMapper.insert(prComment);
        return Result.ok();
    }

    @Override
    public Result<PrCommentVO> updateComment(String owner, String repo, Long prId, Long commentId,
                                             PrCommentUpdateDTO prCommentUpdateDTO, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        if (prCommentUpdateDTO == null || StrUtil.isBlank(prCommentUpdateDTO.getBody())) {
            return Result.fail(ResultCode.BAD_REQUEST, "评论内容不能为空");
        }
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        if (findPr(repositoryId, prId) == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        PrComment comment = findComment(repositoryId, prId, commentId);
        if (comment == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "评论不存在");
        }
        if (!userId.equals(comment.getUserId())) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.NOT_COMMENT_AUTHOR);
        }
        comment.setBody(prCommentUpdateDTO.getBody().trim());
        comment.setUpdateTime(LocalDateTime.now());
        remotePrCommentMapper.updateById(comment);
        return Result.ok(toVO(comment, new HashMap<>()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> deleteComment(String owner, String repo, Long prId, Long commentId, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        if (findPr(repositoryId, prId) == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        PrComment comment = findComment(repositoryId, prId, commentId);
        if (comment == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "评论不存在");
        }
        if (!userId.equals(comment.getUserId()) && !isOwner(repositoryId, userId)) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.CANNOT_DELETE_COMMENT);
        }
        remotePrCommentMapper.deleteById(comment.getId());
        return Result.ok();
    }

    @Override
    public Result<List<PrReviewVO>> listReviews(String owner, String repo, Long prId) {
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        if (findPr(repositoryId, prId) == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        List<PrReview> reviews = remotePrReviewMapper.selectList(
                Wrappers.<PrReview>lambdaQuery()
                        .eq(PrReview::getRepositoryId, repositoryId)
                        .eq(PrReview::getPullRequestId, prId)
                        .orderByAsc(PrReview::getCreateTime)
                        .orderByAsc(PrReview::getId));
        Map<Long, String> usernameCache = new HashMap<>();
        List<PrReviewVO> result = new ArrayList<>(reviews.size());
        for (PrReview review : reviews) {
            result.add(toVO(review, usernameCache));
        }
        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<PrReviewVO> createReview(String owner, String repo, Long prId,
                                           PrReviewCreateDTO prReviewCreateDTO, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        if (prReviewCreateDTO == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "评审内容不能为空");
        }
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        PullRequest pullRequest = findPr(repositoryId, prId);
        if (pullRequest == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "PR 不存在");
        }
        Result<String> member = checkMember(repositoryId, userId);
        if (member != null) {
            return Result.fail(member.getCode(), member.getMessage());
        }
        if (userId.equals(pullRequest.getAuthorId())) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.CANNOT_REVIEW_OWN_PR);
        }
        PrReviewStateEnum state = PrReviewStateEnum.fromValue(prReviewCreateDTO.getState());
        if (state == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "评审状态非法");
        }

        LocalDateTime now = LocalDateTime.now();
        PrReview review = PrReview.builder()
                .repositoryId(repositoryId)
                .pullRequestId(prId)
                .reviewerId(userId)
                .state(state)
                .body(StrUtil.trim(prReviewCreateDTO.getBody()))
                .commitSha(prReviewCreateDTO.getCommitSha())
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
        remotePrReviewMapper.insert(review);
        return Result.ok(toVO(review, new HashMap<>()));
    }

    /** 查询 PR，并限定必须属于该仓库；不存在或跨仓库返回 null */
    private PullRequest findPr(Long repositoryId, Long prId) {
        if (prId == null) {
            return null;
        }
        PullRequest pullRequest = remotePullRequestMapper.selectById(prId);
        if (pullRequest == null || !repositoryId.equals(pullRequest.getRepositoryId())) {
            return null;
        }
        return pullRequest;
    }

    /** OWNER / CONTRIBUTOR 才允许写操作；通过返回 null */
    private Result<String> checkMember(Long repositoryId, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        Collaborator self = remoteCollaboratorMapper.getCollaborator(userId, repositoryId);
        if (self == null || isDeleted(self) || self.getLevel() == null || self.getLevel().isGuest()) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.ONLY_MEMBERS_CAN_CONTRIBUTE);
        }
        return null;
    }

    private boolean isOwner(Long repositoryId, Long userId) {
        if (userId == null) {
            return false;
        }
        Collaborator self = remoteCollaboratorMapper.getCollaborator(userId, repositoryId);
        return self != null && !isDeleted(self)
                && self.getLevel() != null && self.getLevel().isOwner();
    }

    private boolean isDeleted(Collaborator collaborator) {
        return collaborator.getIsDeleted() != null && collaborator.getIsDeleted() == 1;
    }

    /** 查询评论，并限定必须属于该仓库与 PR */
    private PrComment findComment(Long repositoryId, Long prId, Long commentId) {
        if (commentId == null) {
            return null;
        }
        return remotePrCommentMapper.selectOne(Wrappers.<PrComment>lambdaQuery()
                .eq(PrComment::getId, commentId)
                .eq(PrComment::getRepositoryId, repositoryId)
                .eq(PrComment::getPullRequestId, prId));
    }

    private PrReviewVO toVO(PrReview review, Map<Long, String> usernameCache) {
        return PrReviewVO.builder()
                .id(review.getId())
                .pullRequestId(review.getPullRequestId())
                .reviewerId(review.getReviewerId())
                .reviewerName(usernameOf(review.getReviewerId(), usernameCache))
                .state(review.getState())
                .body(review.getBody())
                .commitSha(review.getCommitSha())
                .createTime(review.getCreateTime())
                .build();
    }

    /** 查询某 PR 下所有回复（reply_to_id 非空），按父 id 归组 */
    private Map<Long, List<PrComment>> loadReplies(Long repositoryId, Long prId) {
        List<PrComment> replies = remotePrCommentMapper.selectList(
                Wrappers.<PrComment>lambdaQuery()
                        .eq(PrComment::getRepositoryId, repositoryId)
                        .eq(PrComment::getPullRequestId, prId)
                        .isNotNull(PrComment::getReplyToId)
                        .orderByAsc(PrComment::getCreateTime)
                        .orderByAsc(PrComment::getId));
        return replies.stream().collect(Collectors.groupingBy(PrComment::getReplyToId));
    }

    /**
     * 递归挂载子回复。用 remove 消费子节点，既能避免重复挂载，
     * 也能在脏数据出现 reply 环时自然终止而不是栈溢出。
     */
    private void attachReplies(PrCommentVO parent,
                               Map<Long, List<PrComment>> repliesByParent,
                               Map<Long, String> usernameCache) {
        List<PrComment> children = repliesByParent.remove(parent.getId());
        if (children == null || children.isEmpty()) {
            return;
        }
        List<PrCommentVO> childVOs = new ArrayList<>(children.size());
        for (PrComment child : children) {
            PrCommentVO childVO = toVO(child, usernameCache);
            attachReplies(childVO, repliesByParent, usernameCache);
            childVOs.add(childVO);
        }
        parent.setReplies(childVOs);
    }

    /** PO -> VO，补齐评论人用户名 */
    private PrCommentVO toVO(PrComment comment, Map<Long, String> usernameCache) {
        return PrCommentVO.builder()
                .id(comment.getId())
                .userId(comment.getUserId())
                .userName(usernameOf(comment.getUserId(), usernameCache))
                .body(comment.getBody())
                .commentType(comment.getCommentType())
                .filePath(comment.getFilePath())
                .lineNumber(comment.getLineNumber())
                .diffSide(comment.getDiffSide())
                .commitSha(comment.getCommitSha())
                .replyToId(comment.getReplyToId())
                .reviewId(comment.getReviewId())
                .createTime(comment.getCreateTime())
                .build();
    }

    private String usernameOf(Long userId, Map<Long, String> cache) {
        if (userId == null) {
            return null;
        }
        String cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        String username = userNameCache.usernameOf(userId);
        cache.put(userId, username);
        return username;
    }
}

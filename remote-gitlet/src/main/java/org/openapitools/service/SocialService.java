package org.openapitools.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import common.Result;
import dto.PrCommentCreateDTO;
import dto.PrCommentUpdateDTO;
import dto.PrReviewCreateDTO;
import vo.PrCommentVO;
import vo.PrReviewVO;

import java.util.List;

public interface SocialService {
    /**
     * 分页查询 PR 评论（只对顶级评论分页，回复挂在 replies 上）
     *
     * @param owner       仓库所有者
     * @param repo        仓库名
     * @param prId        PR 的 id
     * @param commentType 1=整体评论 2=行级评论，null 表示全部
     */
    Result<IPage<PrCommentVO>> getComments(
            String owner,
            String repo,
            Long prId,
            Integer commentType,
            Integer pageNum,
            Integer pageSize
    );

    /**
     * 创建 PR 评论
     *
     * @param owner 拥有者
     * @param repo 仓库名
     * @param id pull request的id
     * @param prCommentCreateDTO 评论创建DTO
     */
    Result<String> createComment(String owner, String repo, Long id, PrCommentCreateDTO prCommentCreateDTO, Long userId);

    /**
     * 编辑评论，仅评论作者可操作
     */
    Result<PrCommentVO> updateComment(String owner, String repo, Long prId, Long commentId,
                                      PrCommentUpdateDTO prCommentUpdateDTO, Long userId);

    /**
     * 删除评论，评论作者或仓库 OWNER 可操作（逻辑删除）
     */
    Result<String> deleteComment(String owner, String repo, Long prId, Long commentId, Long userId);

    /**
     * 列出 PR 的全部评审记录（按提交时间升序）
     */
    Result<List<PrReviewVO>> listReviews(String owner, String repo, Long prId);

    /**
     * 提交一次评审；同一评审人可多次提交，合并门禁取每人最新一条
     */
    Result<PrReviewVO> createReview(String owner, String repo, Long prId,
                                    PrReviewCreateDTO prReviewCreateDTO, Long userId);
}

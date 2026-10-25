package org.openapitools.service;

import common.Result;
import dto.AiReviewCreateDTO;
import vo.AiReviewRecordVO;

import java.util.List;

public interface AiReviewService {
    /**
     * 发起一次 AI 审查：先落 RUNNING 记录，再异步调用 AI 服务
     */
    Result<AiReviewRecordVO> createReview(String owner, String repo, AiReviewCreateDTO aiReviewCreateDTO, Long userId);

    /**
     * 分页查询审查记录，pullRequestId / commitSha 条件可组合
     */
    Result<List<AiReviewRecordVO>> listReviews(String owner, String repo, Long pullRequestId,
                                               String commitSha, Integer page, Integer size);

    /**
     * 查询单条审查记录（轮询状态 / 读取结论）
     */
    Result<AiReviewRecordVO> getReview(String owner, String repo, Long recordId);
}

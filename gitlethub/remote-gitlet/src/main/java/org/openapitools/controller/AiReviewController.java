package org.openapitools.controller;

import common.Result;
import dto.AiReviewCreateDTO;
import org.openapitools.filter.JwtAuthFilter;
import org.openapitools.service.AiReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import vo.AiReviewRecordVO;

import java.util.List;

@RestController
@RequestMapping("/api/repos/{owner}/{repo}/ai-reviews")
public class AiReviewController {

    @Autowired
    private AiReviewService aiReviewService;

    /**
     * 发起 AI 审查
     */
    @PostMapping
    public Result<AiReviewRecordVO> createReview(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestBody AiReviewCreateDTO aiReviewCreateDTO,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId) {
        return aiReviewService.createReview(owner, repo, aiReviewCreateDTO, userId);
    }

    /**
     * 查询审查记录
     */
    @GetMapping
    public Result<List<AiReviewRecordVO>> listReviews(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(value = "pull_request_id", required = false) Long pullRequestId,
            @RequestParam(value = "commit_sha", required = false) String commitSha,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return aiReviewService.listReviews(owner, repo, pullRequestId, commitSha, page, size);
    }

    /**
     * 查询单条审查记录
     */
    @GetMapping("/{recordId}")
    public Result<AiReviewRecordVO> getReview(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long recordId) {
        return aiReviewService.getReview(owner, repo, recordId);
    }
}

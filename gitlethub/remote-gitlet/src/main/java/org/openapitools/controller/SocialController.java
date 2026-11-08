package org.openapitools.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import common.Result;
import dto.PrCommentCreateDTO;
import dto.PrCommentUpdateDTO;
import dto.PrReviewCreateDTO;
import org.openapitools.filter.JwtAuthFilter;
import org.openapitools.service.SocialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import vo.PrCommentVO;
import vo.PrReviewVO;
import vo.PullRequestVO;

import java.util.List;

@RestController
public class SocialController {

    @Autowired
    private SocialService socialService;

    /**
     * 获取pull request的评论
     * @param owner 拥有者
     * @param repo 仓库名
     * @param id pull request的id
     * @param commentType 评论类型
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 评论列表
     */
    @GetMapping("/api/repos/{owner}/{repo}/pull-request/{id}/comments")
    public Result<IPage<PrCommentVO>> getComments(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long id,
            @RequestParam(value = "comment_type", required = false) Integer commentType,
            @RequestParam(value = "page_num", defaultValue = "1") Integer pageNum,
            @RequestParam(value = "page_size", defaultValue = "20") Integer pageSize
    ) {
        return socialService.getComments(owner, repo, id, commentType, pageNum, pageSize);
    }

    /**
     * 创建评论
     * @param owner 拥有者
     * @param repo 仓库名
     * @param id pull request的id
     * @param prCommentCreateDTO 评论创建DTO
     * @param userId 用户ID
     * @return ok
     */
    @PostMapping("/api/repos/{owner}/{repo}/pull-request/{id}/comments")
    public Result<String> createComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long id,
            @RequestBody PrCommentCreateDTO prCommentCreateDTO,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId
    ) {
        return socialService.createComment(owner, repo, id, prCommentCreateDTO, userId);
    }

    /**
     * 编辑评论（仅作者）
     */
    @PutMapping("/api/repos/{owner}/{repo}/pull-request/{id}/comments/{commentId}")
    public Result<PrCommentVO> updateComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long id,
            @PathVariable Long commentId,
            @RequestBody PrCommentUpdateDTO prCommentUpdateDTO,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId
    ) {
        return socialService.updateComment(owner, repo, id, commentId, prCommentUpdateDTO, userId);
    }

    /**
     * 删除评论（作者或仓库 OWNER）
     */
    @DeleteMapping("/api/repos/{owner}/{repo}/pull-request/{id}/comments/{commentId}")
    public Result<String> deleteComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long id,
            @PathVariable Long commentId,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId
    ) {
        return socialService.deleteComment(owner, repo, id, commentId, userId);
    }

    /**
     * 列出 PR 的评审记录
     */
    @GetMapping("/api/repos/{owner}/{repo}/pull-request/{id}/reviews")
    public Result<List<PrReviewVO>> listReviews(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long id
    ) {
        return socialService.listReviews(owner, repo, id);
    }

    /**
     * 提交 PR 评审
     */
    @PostMapping("/api/repos/{owner}/{repo}/pull-request/{id}/reviews")
    public Result<PrReviewVO> createReview(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long id,
            @RequestBody PrReviewCreateDTO prReviewCreateDTO,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId
    ) {
        return socialService.createReview(owner, repo, id, prReviewCreateDTO, userId);
    }

    /**
     * 获取当前用户全部待处理pr信息
     */
    @GetMapping("/api/{owner}/pull-requests")
    public Result<List<PullRequestVO>> getAllPrs(@PathVariable String owner, @RequestParam Integer limit) {
        return socialService.getAllPrs(owner, limit);
    }

}

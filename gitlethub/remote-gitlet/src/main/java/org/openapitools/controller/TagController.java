package org.openapitools.controller;

import common.Result;
import dto.TagCreateDTO;
import org.openapitools.filter.JwtAuthFilter;
import org.openapitools.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import vo.TagVO;

import java.util.List;

@RestController
@RequestMapping("/api/repos/{owner}/{repo}/tags")
public class TagController {

    @Autowired
    private TagService tagService;

    /**
     * 列出仓库的标签
     */
    @GetMapping
    public Result<List<TagVO>> listTags(@PathVariable String owner, @PathVariable String repo) {
        return tagService.listTags(owner, repo);
    }

    /**
     * 查询单个标签
     */
    @GetMapping("/{tagName}")
    public Result<TagVO> getTag(@PathVariable String owner,
                                @PathVariable String repo,
                                @PathVariable String tagName) {
        return tagService.getTag(owner, repo, tagName);
    }

    /**
     * 创建标签
     */
    @PostMapping
    public Result<TagVO> createTag(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestBody TagCreateDTO tagCreateDTO,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId) {
        return tagService.createTag(owner, repo, tagCreateDTO, userId);
    }

    /**
     * 删除标签
     */
    @DeleteMapping("/{tagName}")
    public Result<String> deleteTag(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable String tagName,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId) {
        return tagService.deleteTag(owner, repo, tagName, userId);
    }
}

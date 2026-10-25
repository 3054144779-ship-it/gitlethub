package org.openapitools.service;

import common.Result;
import dto.TagCreateDTO;
import vo.TagVO;

import java.util.List;

public interface TagService {
    /**
     * 列出仓库的全部标签（按创建时间倒序）
     */
    Result<List<TagVO>> listTags(String owner, String repo);

    /**
     * 按标签名查询
     */
    Result<TagVO> getTag(String owner, String repo, String tagName);

    /**
     * 创建标签；提交必须存在，标签名在未删除记录中唯一
     */
    Result<TagVO> createTag(String owner, String repo, TagCreateDTO tagCreateDTO, Long userId);

    /**
     * 删除标签（逻辑删除），仅 OWNER / CONTRIBUTOR
     */
    Result<String> deleteTag(String owner, String repo, String tagName, Long userId);
}

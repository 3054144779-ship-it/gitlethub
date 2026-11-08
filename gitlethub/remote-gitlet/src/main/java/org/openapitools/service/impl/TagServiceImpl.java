package org.openapitools.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import common.Result;
import common.ResultCode;
import dto.TagCreateDTO;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteTagMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.TagService;
import org.openapitools.service.support.RepositoryObjectReader;
import org.openapitools.utils.UserNameCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import po.Collaborator;
import po.Tag;
import vo.TagVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TagServiceImpl implements TagService {

    @Autowired
    private RemoteTagMapper remoteTagMapper;
    @Autowired
    private UserNameCache userNameCache;
    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;
    @Autowired
    private RepositoryObjectReader repositoryObjectReader;

    @Override
    public Result<List<TagVO>> listTags(String owner, String repo) {
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        List<Tag> tags = remoteTagMapper.selectList(Wrappers.<Tag>lambdaQuery()
                .eq(Tag::getRepositoryId, repositoryId)
                .orderByDesc(Tag::getCreateTime)
                .orderByDesc(Tag::getId));
        Map<Long, String> usernameCache = new HashMap<>();
        List<TagVO> result = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            result.add(toVO(tag, usernameCache));
        }
        return Result.ok(result);
    }

    @Override
    public Result<TagVO> getTag(String owner, String repo, String tagName) {
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Tag tag = findTag(repositoryId, tagName);
        if (tag == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "标签不存在");
        }
        return Result.ok(toVO(tag, new HashMap<>()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<TagVO> createTag(String owner, String repo, TagCreateDTO tagCreateDTO, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        if (tagCreateDTO == null || StrUtil.isBlank(tagCreateDTO.getTagName())
                || StrUtil.isBlank(tagCreateDTO.getCommitSha())) {
            return Result.fail(ResultCode.BAD_REQUEST, "标签名和提交不能为空");
        }
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkMember(repositoryId, userId);
        if (permission != null) {
            return Result.fail(permission.getCode(), permission.getMessage());
        }

        String tagName = tagCreateDTO.getTagName().trim();
        if (!isValidTagName(tagName)) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.TAG_NAME_INVALID);
        }
        if (findTag(repositoryId, tagName) != null) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.TAG_ALREADY_EXISTS);
        }
        String commitSha = tagCreateDTO.getCommitSha().trim();
        if (repositoryObjectReader.readCommitObject(
                repositoryObjectReader.resolvePaths(owner, repo), commitSha) == null) {
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }

        Tag tag = new Tag();
        tag.setRepositoryId(repositoryId);
        tag.setTagName(tagName);
        tag.setCommitSha(commitSha);
        tag.setUserId(userId);
        tag.setDescription(StrUtil.trim(tagCreateDTO.getDescription()));
        tag.setCreateTime(LocalDateTime.now());
        tag.setIsDeleted(0);
        remoteTagMapper.insert(tag);
        return Result.ok(toVO(tag, new HashMap<>()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> deleteTag(String owner, String repo, String tagName, Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkMember(repositoryId, userId);
        if (permission != null) {
            return permission;
        }
        Tag tag = findTag(repositoryId, tagName);
        if (tag == null) {
            return Result.fail(ResultCode.BAD_REQUEST, "标签不存在");
        }
        remoteTagMapper.deleteById(tag.getId());
        return Result.ok();
    }

    /** 按仓库 + 标签名查询；逻辑删除的记录由 @TableLogic 自动排除 */
    private Tag findTag(Long repositoryId, String tagName) {
        if (StrUtil.isBlank(tagName)) {
            return null;
        }
        return remoteTagMapper.selectOne(Wrappers.<Tag>lambdaQuery()
                .eq(Tag::getRepositoryId, repositoryId)
                .eq(Tag::getTagName, tagName));
    }

    /** OWNER / CONTRIBUTOR 才允许写操作；通过返回 null */
    private Result<String> checkMember(Long repositoryId, Long userId) {
        Collaborator self = remoteCollaboratorMapper.getCollaborator(userId, repositoryId);
        boolean deleted = self != null && self.getIsDeleted() != null && self.getIsDeleted() == 1;
        if (self == null || deleted || self.getLevel() == null || self.getLevel().isGuest()) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.ONLY_MEMBERS_CAN_CONTRIBUTE);
        }
        return null;
    }

    /**
     * Git ref 命名规则的关键约束：不以 - 或 . 开头，不以 / 或 . 结尾，
     * 不出现 .. // @{ ~ ^ : ? * [ \、控制字符与空白，不包含 .lock。
     */
    private boolean isValidTagName(String name) {
        if (StrUtil.isBlank(name) || name.startsWith("-") || name.startsWith(".")) {
            return false;
        }
        if (name.endsWith("/") || name.endsWith(".")
                || name.contains("..") || name.contains("//")
                || name.contains("@{") || name.contains(".lock")
                || "@".equals(name)) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (c <= 0x20 || c == 0x7f || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private TagVO toVO(Tag tag, Map<Long, String> usernameCache) {
        return TagVO.builder()
                .id(tag.getId())
                .tagName(tag.getTagName())
                .commitSha(tag.getCommitSha())
                .userId(tag.getUserId())
                .userName(usernameOf(tag.getUserId(), usernameCache))
                .description(tag.getDescription())
                .createTime(tag.getCreateTime())
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

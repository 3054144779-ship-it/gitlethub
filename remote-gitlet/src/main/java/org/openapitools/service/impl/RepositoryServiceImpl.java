package org.openapitools.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import common.Result;
import common.ResultCode;
import dto.CollaboratorCreateDTO;
import dto.PageDTO;
import dto.RepositoryCreateDTO;
import enums.CollaboratorLevelEnum;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import po.Collaborator;
import po.Repository;
import po.User;
import vo.CollaboratorVO;
import vo.RepositoryVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RepositoryServiceImpl extends ServiceImpl<RemoteRepositoryMapper, Repository> implements RepositoryService{
    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Autowired
    private RemoteUserMapper remoteUserMapper;
    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;

    /**
     * 更新远程仓库配置
     * @param repositoryCreateDTO 远程仓库配置DTO
     * @return 更新结果
     */
    @Override
    public Result<String> updateRemoteRepository(RepositoryCreateDTO repositoryCreateDTO) {
        String owner = repositoryCreateDTO.getOwner();
        String repo = repositoryCreateDTO.getRepo();
        // 获取当前用户ID(根据用户名字查)
        Long ownerId = remoteUserMapper.getUserId(owner);
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(ownerId, repo);

        boolean success = lambdaUpdate()
                .set(Repository::getName, repositoryCreateDTO.getRepo())
                .set(Repository::getDescription, repositoryCreateDTO.getDescription())
                .set(Repository::getVisibility, repositoryCreateDTO.getVisibility())
                .set(Repository::getAllowAutoPr, repositoryCreateDTO.getAllowAutoPr())
                .eq(Repository::getId, repositoryId)
                .update();

        if (!success) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, MsgConstant.REMOTE_REPOSITORY_UPDATE_FAILED);
        }

        return Result.ok();
    }

    /**
     * 获取远程仓库配置
     * @param repositoryId 远程仓库ID
      * @return 远程仓库配置
     */
    @Override
    public Result<RepositoryVO> getRemoteRepository(String repositoryId) {
        Repository repository = lambdaQuery().eq(Repository::getId, repositoryId).one();

        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }

        RepositoryVO repositoryVO = BeanUtil.copyProperties(repository, RepositoryVO.class);
        repositoryVO.setOwnerName(usernameOf(repository.getOwnerId()));

        return Result.ok(repositoryVO);
    }

    /**
     * 获取所有远程仓库配置
     * @param owner 远程仓库所有者
     * @param keyword 远程仓库关键字
     * @param pageDTO 分页参数
     * @return 远程仓库列表
     */
    @Override
    public Result<List<RepositoryVO>> getAllRemoteRepository(String owner, String keyword, PageDTO pageDTO) {
        Page<Repository> page = new Page<>(pageDTO.getPage(), pageDTO.getSize());
        Long ownerId = remoteUserMapper.getUserId(owner);
        Page<Repository> repositoryPage = lambdaQuery()
                .eq(Repository::getOwnerId, ownerId)
                // like(condition, column, value)：keyword 为空时不追加该条件
                .like(StrUtil.isNotBlank(keyword), Repository::getName, keyword)
                .page(page);
        Map<Long, String> usernameCache = new HashMap<>();
        List<RepositoryVO> repositoryVOList = repositoryPage.getRecords().stream()
                .map(repository -> {
                    RepositoryVO vo = BeanUtil.copyProperties(repository, RepositoryVO.class);
                    vo.setOwnerName(usernameOf(repository.getOwnerId(), usernameCache));
                    return vo;
                })
                .toList();
        return Result.ok(repositoryVOList);
    }

    /**
     * 按 owner/repo 获取远程仓库配置
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @return 远程仓库配置
     */
    @Override
    public Result<RepositoryVO> getRepositoryByOwnerAndRepo(String owner, String repo) {
        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        RepositoryVO vo = BeanUtil.copyProperties(repository, RepositoryVO.class);
        vo.setOwnerName(owner);
        return Result.ok(vo);
    }

    /**
     * 列出仓库的协作者（含用户名）
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @return 协作者列表
     */
    @Override
    public Result<List<CollaboratorVO>> listCollaborators(String owner, String repo) {
        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        List<Collaborator> collaborators = remoteCollaboratorMapper.selectList(
                Wrappers.<Collaborator>lambdaQuery()
                        .eq(Collaborator::getRepositoryId, repository.getId())
                        .orderByDesc(Collaborator::getLevel)
                        .orderByAsc(Collaborator::getCreateTime));
        Map<Long, String> usernameCache = new HashMap<>();
        List<CollaboratorVO> result = new ArrayList<>();
        for (Collaborator collaborator : collaborators) {
            CollaboratorVO vo = new CollaboratorVO();
            vo.setUserId(collaborator.getUserId());
            vo.setUsername(usernameOf(collaborator.getUserId(), usernameCache));
            vo.setLevel(collaborator.getLevel());
            vo.setAutoCreatePr(collaborator.getAutoCreatePr());
            vo.setCreateTime(collaborator.getCreateTime());
            result.add(vo);
        }
        return Result.ok(result);
    }

    /**
     * 添加协作者，仅仓库 OWNER 可操作
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @param collaboratorCreateDTO 待添加的协作者
     * @return 添加结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> addCollaborator(String owner, String repo, CollaboratorCreateDTO collaboratorCreateDTO) {
        if (collaboratorCreateDTO == null || StrUtil.isBlank(collaboratorCreateDTO.getUsername())) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_USER_DOES_NOT_EXIST);
        }
        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkOwner(repository, owner);
        if (permission != null) {
            return permission;
        }
        CollaboratorLevelEnum level = toLevel(collaboratorCreateDTO.getLevel());
        // 不再通过该接口授予/改动 OWNER，避免出现多个所有者
        if (level == null || level.isOwner()) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_COLLABORATOR_LEVEL_IS_INVALID);
        }
        Long targetId = remoteUserMapper.getUserId(collaboratorCreateDTO.getUsername());
        if (targetId == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND, MsgConstant.THE_USER_DOES_NOT_EXIST);
        }

        Collaborator existing = remoteCollaboratorMapper.getCollaborator(targetId, repository.getId());
        if (existing != null) {
            // 已存在（可能是被逻辑删除过的行）→ 复活并更新等级
            remoteCollaboratorMapper.reactivateCollaborator(targetId, repository.getId(), level.getCode());
            return Result.ok();
        }
        remoteCollaboratorMapper.insert(Collaborator.builder()
                .repositoryId(repository.getId())
                .userId(targetId)
                .level(level)
                .autoCreatePr(1)
                .createTime(LocalDateTime.now())
                .isDeleted(0)
                .build());
        return Result.ok();
    }

    /**
     * 移除协作者，仅仓库 OWNER 可操作
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @param userId 被移除的协作者用户ID
     * @return 移除结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> removeCollaborator(String owner, String repo, Long userId) {
        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkOwner(repository, owner);
        if (permission != null) {
            return permission;
        }
        Collaborator target = remoteCollaboratorMapper.getCollaborator(userId, repository.getId());
        if (target == null || target.getIsDeleted() != null && target.getIsDeleted() == 1) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_COLLABORATOR_DOES_NOT_EXIST);
        }
        if (target.getLevel() != null && target.getLevel().isOwner()) {
            // 所有者不能被移除
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.NOT_REPOSITORY_OWNER);
        }
        remoteCollaboratorMapper.deleteById(target.getId());
        return Result.ok();
    }

    /** owner/repo -> Repository，不存在返回 null */
    private Repository findRepository(String owner, String repo) {
        Long ownerId = remoteUserMapper.getUserId(owner);
        if (ownerId == null) {
            return null;
        }
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(ownerId, repo);
        if (repositoryId == null) {
            return null;
        }
        return remoteRepositoryMapper.selectById(repositoryId);
    }

    /** 校验调用者是否为仓库 OWNER；通过返回 null，否则返回失败结果 */
    private Result<String> checkOwner(Repository repository, String owner) {
        Long ownerId = remoteUserMapper.getUserId(owner);
        Collaborator self = remoteCollaboratorMapper.getCollaborator(ownerId, repository.getId());
        if (self == null || self.getLevel() == null || !self.getLevel().isOwner()) {
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.NOT_REPOSITORY_OWNER);
        }
        return null;
    }

    /** 等级 code -> 枚举；未知 code 返回 null；code 为空默认贡献者 */
    private CollaboratorLevelEnum toLevel(Integer code) {
        if (code == null) {
            return CollaboratorLevelEnum.CONTRIBUTOR;
        }
        for (CollaboratorLevelEnum level : CollaboratorLevelEnum.values()) {
            if (level.getCode() == code) {
                return level;
            }
        }
        return null;
    }

    private String usernameOf(Long userId) {
        return usernameOf(userId, new HashMap<>());
    }

    private String usernameOf(Long userId, Map<Long, String> cache) {
        if (userId == null) {
            return null;
        }
        String cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        User user = remoteUserMapper.selectById(userId);
        String username = user == null ? null : user.getUsername();
        cache.put(userId, username);
        return username;
    }
}

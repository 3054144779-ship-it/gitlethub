package org.openapitools.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.Result;
import common.ResultCode;
import dto.CollaboratorCreateDTO;
import dto.PageDTO;
import dto.RepositoryCreateDTO;
import enums.CollaboratorLevelEnum;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.RepositoryService;
import org.openapitools.service.support.RepositoryObjectReader;
import org.openapitools.utils.RedisCacheUtils;
import org.openapitools.utils.UserNameCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import po.Collaborator;
import po.Repository;
import po.User;
import vo.CollaboratorVO;
import vo.RepositoryVO;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class RepositoryServiceImpl extends ServiceImpl<RemoteRepositoryMapper, Repository> implements RepositoryService{
    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Autowired
    private RemoteUserMapper remoteUserMapper;
    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;
    @Autowired
    private RepositoryObjectReader repositoryObjectReader;
    @Autowired
    private RedisCacheUtils redisCacheUtils;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserNameCache userNameCache;

    private static final Integer CACHE_TTL_BASE_SECONDS = 60;
    private static final Integer CACHE_TTL_JITTER_SECONDS = 30;

    private static final String REPO_DETAIL_KEY_PREFIX = "repository:ownerRepo:";
    private static final String COLLABORATORS_KEY_PREFIX = "repository:collaborators:";

    /**
     * 更新远程仓库配置
     * @param repositoryCreateDTO 远程仓库配置DTO
     * @return 更新结果
     */
    @Override
    public Result<String> updateRemoteRepository(Long userId, RepositoryCreateDTO repositoryCreateDTO) {
        String repo = repositoryCreateDTO.getRepo();
        // 获取当前用户ID(根据用户名字查)
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(userId, repo);


        boolean success = lambdaUpdate()
                .set(Repository::getName, repositoryCreateDTO.getRepo())
                .set(Repository::getDescription, repositoryCreateDTO.getDescription())
                .set(Repository::getVisibility, repositoryCreateDTO.getVisibility())
                .set(Repository::getAllowAutoPr, repositoryCreateDTO.getAllowAutoPr())
                .eq(Repository::getId, repositoryId)
                .update();

        if (!success) {
            return Result.fail(ResultCode.REMOTE_REPOSITORY_UPDATE_FAILED, MsgConstant.NOT_THE_OWNER_OF_THE_WAREHOUSE);
        }

        if (repositoryId != null) {
            evict("repositoryId:" + repositoryId);
            // 可见性可能被改过，拦截器用的仓库行缓存必须一起失效
            repositoryObjectReader.evictRepository(repositoryId);
            // owner/repo 这条 key 也指向同一份详情，必须一起失效
            User owner = remoteUserMapper.selectById(userId);
            if (owner != null) {
                evict(repoDetailKey(owner.getUsername(), repo));
            }
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
        String key = "repositoryId:" + repositoryId;
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return Result.ok(objectMapper.readValue(cached, RepositoryVO.class));
            }
        } catch (Exception e) {
            // 读缓存失败（反序列化失败 / Redis 不可用）一律回源，并清掉脏 key
            log.warn("仓库详情缓存读取失败，回源数据库，key={}", key, e);
            redisCacheUtils.delete(key);
        }
        Repository repository = lambdaQuery().eq(Repository::getId, repositoryId).one();

        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }

        RepositoryVO repositoryVO = BeanUtil.copyProperties(repository, RepositoryVO.class);
        repositoryVO.setOwnerName(usernameOf(repository.getOwnerId()));

        try {
            Duration TTL = Duration.ofSeconds(CACHE_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS));
            redisCacheUtils.set(key, objectMapper.writeValueAsString(repositoryVO), TTL);
        } catch (Exception e) {
            // 序列化失败、Redis 连接/超时都兜住：缓存是旁路，绝不能影响主流程
            log.warn("仓库详情缓存写入失败，key={}", key, e);
        }
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
    public Result<List<RepositoryVO>> getAllRemoteRepository(
            String owner, String keyword, PageDTO pageDTO, Long userId) {
        Page<Repository> page = new Page<>(pageDTO.getPage(), pageDTO.getSize());
        Long ownerId = remoteUserMapper.getUserId(owner);
        // 可见性条件下推到 SQL：分页与 total 才准确，也避免逐行查协作者表
        List<Repository> records =
                remoteRepositoryMapper.selectVisibleByOwner(page, ownerId, StrUtil.trim(keyword), userId);

        Map<Long, String> usernameCache = new HashMap<>();
        List<RepositoryVO> repositoryVOList = records.stream()
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
        String key = repoDetailKey(owner, repo);
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return Result.ok(objectMapper.readValue(cached, RepositoryVO.class));
            }
        } catch (Exception e) {
            // 缓存是旁路：读取/反序列化失败一律回源，并清掉脏 key
            log.warn("仓库详情缓存读取失败，回源数据库，key={}", key, e);
            evict(key);
        }

        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        RepositoryVO vo = BeanUtil.copyProperties(repository, RepositoryVO.class);
        vo.setOwnerName(owner);
        try {
            redisCacheUtils.set(key, objectMapper.writeValueAsString(vo), cacheTtl());
        } catch (Exception e) {
            log.warn("仓库详情缓存写入失败，key={}", key, e);
        }
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
        String key = collaboratorsKey(owner, repo);
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return Result.ok(objectMapper.readValue(cached, new TypeReference<List<CollaboratorVO>>() {
                }));
            }
        } catch (Exception e) {
            log.warn("协作者列表缓存读取失败，回源数据库，key={}", key, e);
            evict(key);
        }

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
        try {
            redisCacheUtils.set(key, objectMapper.writeValueAsString(result), cacheTtl());
        } catch (Exception e) {
            log.warn("协作者列表缓存写入失败，key={}", key, e);
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
    public Result<String> addCollaborator(Long userId, String owner, String repo, CollaboratorCreateDTO collaboratorCreateDTO) {
        if (collaboratorCreateDTO == null || StrUtil.isBlank(collaboratorCreateDTO.getUsername())) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_USER_DOES_NOT_EXIST);
        }
        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkOwner(repository, userId);
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
            evict(collaboratorsKey(owner, repo));
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
        evict(collaboratorsKey(owner, repo));
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
    public Result<String> removeCollaborator(Long operatorId, String owner, String repo, Long userId) {
        Repository repository = findRepository(owner, repo);
        if (repository == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Result<String> permission = checkOwner(repository, operatorId);
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
        evict(collaboratorsKey(owner, repo));
        return Result.ok();
    }

    /**
     * 更新协作者信息(仅对当前仓库生效)
     * @param userId 操作者用户ID
     * @param autoCreatePr 是否自动创建 PR
     * @return 更新结果
     */
    @Override
    public Result<String> updateCollaborator(String owner, String repo, Long userId, boolean autoCreatePr) {
        LambdaUpdateWrapper<Collaborator> wrapper = Wrappers.lambdaUpdate();
        wrapper.set(Collaborator::getAutoCreatePr, autoCreatePr)
                .eq(Collaborator::getUserId, userId)
                .eq(Collaborator::getRepositoryId, repositoryObjectReader.resolveRepositoryId(owner, repo));
        int line = remoteCollaboratorMapper.update(null, wrapper);
        if (line < 1) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_COLLABORATOR_DOES_NOT_EXIST);
        }
        evict(collaboratorsKey(owner, repo));
        return Result.ok();
    }

    /** owner/repo -> Repository，不存在返回 null；解析结果与拦截器共用缓存 */
    private Repository findRepository(String owner, String repo) {
        return repositoryObjectReader.resolveRepository(owner, repo);
    }

    /** 校验调用者是否为仓库 OWNER；通过返回 null，否则返回失败结果 */
    private Result<String> checkOwner(Repository repository, Long userId) {
//        Long ownerId = remoteUserMapper.getUserId(owner);
        Collaborator self = remoteCollaboratorMapper.getCollaborator(userId, repository.getId());
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
        // 局部 map 去重同一页里的重复用户，跨请求命中的是 Redis
        String username = userNameCache.usernameOf(userId);
        cache.put(userId, username);
        return username;
    }

    private String repoDetailKey(String owner, String repo) {
        return REPO_DETAIL_KEY_PREFIX + owner + ":" + repo;
    }

    private String collaboratorsKey(String owner, String repo) {
        return COLLABORATORS_KEY_PREFIX + owner + ":" + repo;
    }

    /** 基础 TTL + 随机抖动，避免同一秒集体过期把数据库打穿 */
    private Duration cacheTtl() {
        return Duration.ofSeconds(CACHE_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS));
    }

    /** 缓存清理失败不能影响写请求 */
    private void evict(String key) {
        try {
            redisCacheUtils.delete(key);
        } catch (Exception e) {
            log.warn("缓存清理失败，key={}", key, e);
        }
    }
}

package org.openapitools.service;

import com.baomidou.mybatisplus.extension.service.IService;
import common.Result;
import dto.CollaboratorCreateDTO;
import dto.PageDTO;
import dto.RepositoryCreateDTO;
import po.Repository;
import vo.CollaboratorVO;
import vo.RepositoryVO;

import java.util.List;

public interface RepositoryService extends IService<Repository> {
    /**
     * 更新远程仓库配置
     * @param userId 用户ID
     * @param repositoryCreateDTO 远程仓库配置DTO
     * @return 更新结果
     */
    Result<String> updateRemoteRepository(Long userId, RepositoryCreateDTO repositoryCreateDTO);

    /**
     * 获取远程仓库配置
     * @param repositoryId 远程仓库ID
     * @return 远程仓库配置
     */
    Result<RepositoryVO> getRemoteRepository(String repositoryId);

    /**
     * 获取所有远程仓库配置
     * @param owner 远程仓库所有者
     * @param keyword 远程仓库关键字
     * @param pageDTO 分页参数
     * @return 远程仓库列表
     */
    Result<List<RepositoryVO>> getAllRemoteRepository(String owner, String keyword, PageDTO pageDTO, Long userId);

    /**
     * 按 owner/repo 获取远程仓库配置（前端路由 /:owner/:repo 使用）
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @return 远程仓库配置
     */
    Result<RepositoryVO> getRepositoryByOwnerAndRepo(String owner, String repo);

    /**
     * 列出仓库的协作者（含用户名）
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @return 协作者列表
     */
    Result<List<CollaboratorVO>> listCollaborators(String owner, String repo);

    /**
     * 添加协作者，仅仓库 OWNER 可操作
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @param collaboratorCreateDTO 待添加的协作者
     * @return 添加结果
     */
    Result<String> addCollaborator(Long userId, String owner, String repo, CollaboratorCreateDTO collaboratorCreateDTO);

    /**
     * 移除协作者，仅仓库 OWNER 可操作
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @param userId 被移除的协作者用户ID
     * @return 移除结果
     */
    Result<String> removeCollaborator(Long operatorId, String owner, String repo, Long userId);

    /**
     * 更新协作者，仅仓库 OWNER 可操作
     * @param userId 操作者用户ID
      * @param autoCreatePr 是否自动创建 PR
      * @return 操作结果
     */
    Result<String> updateCollaborator(String owner, String repo, Long userId, boolean autoCreatePr);
}

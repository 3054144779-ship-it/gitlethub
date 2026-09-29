package org.openapitools.service;

import com.baomidou.mybatisplus.extension.service.IService;
import common.Result;
import dto.RepositoryCreateDTO;
import po.Repository;
import vo.RepositoryVO;

public interface RepositoryService extends IService<Repository> {
    /**
     * 更新远程仓库配置
     * @param repositoryCreateDTO 远程仓库配置DTO
     * @return 更新结果
     */
    Result<String> updateRemoteRepository(RepositoryCreateDTO repositoryCreateDTO);

    /**
     * 获取远程仓库配置
     * @param repositoryId 远程仓库ID
     * @return 远程仓库配置
     */
    Result<RepositoryVO> getRemoteRepository(String repositoryId);
}

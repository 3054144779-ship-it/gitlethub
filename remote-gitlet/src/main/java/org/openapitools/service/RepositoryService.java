package org.openapitools.service;

import com.baomidou.mybatisplus.extension.service.IService;
import common.Result;
import dto.RepositoryCreateDTO;
import po.Repository;

public interface RepositoryService extends IService<Repository> {
    /**
     * 更新远程仓库配置
     * @param repositoryCreateDTO 远程仓库配置DTO
     * @return 更新结果
     */
    Result<String> updateRemoteRepository(RepositoryCreateDTO repositoryCreateDTO);
}

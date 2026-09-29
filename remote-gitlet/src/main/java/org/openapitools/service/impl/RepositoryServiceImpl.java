package org.openapitools.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import common.Result;
import common.ResultCode;
import dto.RepositoryCreateDTO;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import po.Repository;
import vo.RepositoryVO;

@Service
public class RepositoryServiceImpl extends ServiceImpl<RemoteRepositoryMapper, Repository> implements RepositoryService{
    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Autowired
    private RemoteUserMapper remoteUserMapper;

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

        return Result.ok(repositoryVO);
    }
}

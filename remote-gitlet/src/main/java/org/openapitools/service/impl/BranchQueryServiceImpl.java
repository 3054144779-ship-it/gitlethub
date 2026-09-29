package org.openapitools.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import common.Result;
import common.ResultCode;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.BranchQueryService;
import org.openapitools.service.support.RepositoryObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import po.BranchPointer;

import java.util.List;

/**
 * 分支查询服务实现
 */
@Service
public class BranchQueryServiceImpl implements BranchQueryService {

    @Autowired
    private RepositoryObjectReader reader;

    @Autowired
    private RemoteBranchPointerMapper remoteBranchPointer;

    @Override
    public Result<List<BranchPointer>> listBranches(String owner, String repo) {
        Long repositoryId = reader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        List<BranchPointer> branches = remoteBranchPointer.selectList(
                Wrappers.<BranchPointer>lambdaQuery()
                        .eq(BranchPointer::getRepositoryId, repositoryId)
                        .orderByDesc(BranchPointer::getIsDefault)
                        .orderByAsc(BranchPointer::getBranchName));
        return Result.ok(branches);
    }
}

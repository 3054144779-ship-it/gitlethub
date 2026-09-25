package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.BranchPointer;

@Mapper
public interface RemoteBranchPointerMapper extends BaseMapper<BranchPointer> {
    /**
     * 根据owner, repositoryId, branch查询BranchPointer
     * @param owner 用户名
     * @param repositoryId 仓库id
     * @param branch 分支名
     * @return BranchPointer
     */
    BranchPointer getBranchPointer(String owner, Long repositoryId, String branch);

    /**
     * 根据id更新BranchPointer
      * @param id 分支指针id
      * @param sha1 分支指针指向的新的commit的sha1
     */
    void updateBranchPointer(Long id, String sha1, Integer version);
}

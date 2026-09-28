package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.CommitParent;

import java.util.List;

@Mapper
public interface RemoteCommitPointerMapper extends BaseMapper<CommitParent> {
    /**
     * 根据仓库id和sha值获取父提交的sha值列表
     * @param repositoryId 仓库id
     * @param sha 提交的sha值
     * @return 父提交的sha值列表
     */
    List<String> getParents(Long repositoryId, String sha);

    /**
     * 插入合并提交的父提交
      * @param repositoryId 仓库id
      * @param mergeSha 合并提交的sha值
      * @param targetSha 目标提交的sha值
     */
    void insertCommitParent(Long repositoryId, String mergeSha, String targetSha);
}

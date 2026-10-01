package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import po.Repository;

import java.util.List;

@Mapper
public interface RemoteRepositoryMapper extends BaseMapper<Repository> {

    /**
     * 根据owner和repo名获取repository id
     * @param ownerId owner
     * @param repo repo name
     * @return repository id
     */
    Long getRepositoryId(Long ownerId, String repo);

    /**
     * 分页查询某 owner 名下、对 userId 可见的仓库。
     * 可见条件（公开 或 是 OWNER/CONTRIBUTOR）下推到 SQL，保证过滤后的分页与 total 正确，
     * 也避免逐行查协作者表。
     * @param page 分页参数，MP 会回填 records 与 total
     * @param ownerId 仓库所有者的用户ID
     * @param keyword 仓库名关键字，空则不参与过滤
     * @param userId 调用者ID，可为 null（未登录只看公开仓库）
     * @return 当前页的仓库列表
     */
    List<Repository> selectVisibleByOwner(IPage<Repository> page,
                                          @Param("ownerId") Long ownerId,
                                          @Param("keyword") String keyword,
                                          @Param("userId") Long userId);
}

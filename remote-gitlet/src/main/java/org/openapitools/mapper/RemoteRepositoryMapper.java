package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.Repository;

@Mapper
public interface RemoteRepositoryMapper extends BaseMapper<Repository> {

    /**
     * 根据owner和repo名获取repository id
     * @param owner owner
     * @param repo repo name
     * @return repository id
     */
    Long getRepositoryId(String owner, String repo);
}

package org.openapitools.mapper;

import po.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RemoteUserMapper extends BaseMapper<User> {

    Long getUserId(String username);
}

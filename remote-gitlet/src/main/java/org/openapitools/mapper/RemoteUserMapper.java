package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.openapitools.model.po.User;

@Mapper
public interface RemoteUserMapper extends BaseMapper<User> {

}

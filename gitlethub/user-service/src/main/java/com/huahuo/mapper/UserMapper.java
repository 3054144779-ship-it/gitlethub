package com.huahuo.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dto.UserLoginDTO;
import org.apache.ibatis.annotations.Mapper;
import po.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 用户登录
     * @param userLoginDTO 用户信息
     * @return 登录结果
     */
    User login(UserLoginDTO userLoginDTO);
}

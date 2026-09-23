package com.huahuo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import dto.UserDTO;
import dto.UserLoginDTO;
import po.User;
import vo.UserLoginVO;

public interface UserService extends IService<User> {

    /**
     * 用户注册
     * @param userDTO 用户信息
     */
    void registration(UserDTO userDTO);

    /**
     * 用户登录
     * @param userLoginDTO 用户信息
     */
    UserLoginVO login(UserLoginDTO userLoginDTO);

    /**
     * 用户登出
     */
    void logout(String token);
}

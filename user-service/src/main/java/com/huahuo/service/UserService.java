package com.huahuo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import dto.UserDTO;
import dto.UserLoginDTO;
import dto.UserPageSearch;
import dto.UserSearchVO;
import po.User;
import vo.UserLoginVO;

import java.util.List;

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
     * 用户退出登录
     * @param token 登录令牌
     */
    void logout(String token);

    /**
     * 根据用户ID获取用户信息
     * @param userPageSearch 用户分页查询条件
     * @return 用户信息
     */
    IPage<UserSearchVO> getUserByUserNamePage(UserPageSearch userPageSearch);

    /**
     * 根据用户名查询用户
      * @param userId 用户ID
      * @return 用户信息
     */
    User searchUser(Long userId);
}

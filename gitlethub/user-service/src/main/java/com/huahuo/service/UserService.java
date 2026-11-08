package com.huahuo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import common.Result;
import dto.UserDTO;
import dto.UserLoginDTO;
import dto.UserPageSearch;
import dto.UserSearchVO;
import dto.UserUpdateDTO;
import po.User;
import vo.UserLoginVO;
import vo.UserProfileVO;

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
     * 根据用户ID查询用户资料（不含密码）
     * @param userId 用户ID
      * @return 用户信息
     */
    UserProfileVO searchUser(Long userId);

    /**
     * 修改当前登录用户的资料：邮箱按传入值更新，密码仅在 newPassword 非空时修改
     * @param userId 当前登录用户ID
     * @param userUpdateDTO 修改内容
     * @return 更新后的用户资料
     */
    Result<UserProfileVO> updateProfile(Long userId, UserUpdateDTO userUpdateDTO);
}

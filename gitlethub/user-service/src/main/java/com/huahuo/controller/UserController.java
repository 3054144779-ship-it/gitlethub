package com.huahuo.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.huahuo.filter.UserIdentityFilter;
import com.huahuo.message.MsgConstant;
import com.huahuo.service.UserService;
import common.Result;
import common.ResultCode;
import dto.UserDTO;
import dto.UserLoginDTO;
import dto.UserPageSearch;
import dto.UserSearchVO;
import dto.UserUpdateDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import vo.UserLoginVO;
import vo.UserProfileVO;

import java.util.List;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 用户注册
     * @param userDTO 用户信息
     * @return 注册结果
     */
    @PostMapping("/registration")
    public Result<String> registration(@RequestBody UserDTO userDTO) {
        userService.registration(userDTO);
        return Result.ok();
    }

    /**
     * 用户登录
     * @param userLoginDTO 用户信息
     * @return 登录结果
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
        return Result.ok(userService.login(userLoginDTO));
    }

    /**
     * 用户登出
     * @return 登出结果
     */
    @PostMapping("/logout")
    public Result<String> logout(@RequestHeader("Authorization") String token) {
        userService.logout(token);
        return Result.ok();
    }


    /**
     * 根据用户名查询用户
      * @param userPageSearch 用户分页查询参数
     * @return 用户信息
     */
    @GetMapping("/username/list")
    public Result<IPage<UserSearchVO>> getUserByUserNamePage(UserPageSearch userPageSearch) {
        return Result.ok(userService.getUserByUserNamePage(userPageSearch));
    }

    /**
     * 查询用户的详细信息（不含密码）
     * @param userId 用户ID
     * @return 用户资料
     */
    @GetMapping("/{user_id}")
    public Result<UserProfileVO> searchUser(@PathVariable("user_id") Long userId) {
        return Result.ok(userService.searchUser(userId));
    }

    /**
     * 修改当前登录用户的个人资料
     * @param userUpdateDTO 邮箱 / 原密码 / 新密码
     * @param userId 由 UserIdentityFilter 从 Authorization 解析出的当前用户ID
     * @return 更新后的用户资料
     */
    @PutMapping("/profile")
    public Result<UserProfileVO> updateProfile(@RequestBody UserUpdateDTO userUpdateDTO,
                                               @RequestAttribute(value = UserIdentityFilter.USER_ID_ATTRIBUTE, required = false) Long userId) {
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        return userService.updateProfile(userId, userUpdateDTO);
    }

}

package com.huahuo.controller;

import com.huahuo.service.UserService;
import common.Result;
import dto.UserDTO;
import dto.UserLoginDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import po.User;
import vo.UserLoginVO;

@Controller
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

}

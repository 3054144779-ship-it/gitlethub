package com.huahuo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huahuo.mapper.UserMapper;
import com.huahuo.message.MsgConstant;
import com.huahuo.service.UserService;
import common.Result;
import common.ResultCode;
import dto.UserDTO;
import dto.UserLoginDTO;
import dto.UserPageSearch;
import dto.UserSearchVO;
import dto.UserUpdateDTO;
import exception.LoginException;
import jwt.KeyPairManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import po.User;
import vo.UserLoginVO;
import vo.UserProfileVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    // 用户Mapper
    @Autowired
    private UserMapper userMapper;

    // 密码加密
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    // JWT工具类
    @Autowired
    private KeyPairManager keyPairManager;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用户注册
     * @param userDTO 用户信息
     */
    @Override
    public void registration(UserDTO userDTO) {
        User user = BeanUtil.copyProperties(userDTO, User.class);
        // 密码加密
        user.setPassword(bCryptPasswordEncoder.encode(userDTO.getPassword()));
        userMapper.insert(user);
    }

    /**
     * 用户登录
     * @param userLoginDTO 用户信息
     */
    @Override
    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        // 查询用户信息，list() 防止重名
        List<User> userList = lambdaQuery().eq(User::getUsername, userLoginDTO.getUsername()).list();
        log.info("userList: {}", userList.toString());
        if (CollUtil.isEmpty(userList)) {
            // 如果当前用户不存在则返回报错信息
            log.info("用户不存在");
            throw new LoginException(MsgConstant.INCORRECT_USERNAME_OR_PASSWORD);
        }

        // 密码错误
        if (userList.stream()
                .noneMatch(user ->
                        bCryptPasswordEncoder.matches(userLoginDTO.getPassword(), user.getPassword()))) {
            log.info("密码错误");
            throw new LoginException(MsgConstant.INCORRECT_USERNAME_OR_PASSWORD);
        }
        String token = keyPairManager.createToken(userList.get(0).getId(), userList.get(0).getUsername());
        log.info("token: {}", token);
        // 登录成功
        return UserLoginVO.builder()
                .token(token)
                .userId(userList.get(0).getId())
                .username(userList.get(0).getUsername())
                .build();
    }

    /**
     * 用户登出：剥前缀 / 解析 exp / 写黑名单统一交给 KeyPairManager，
     * 保证与网关查询黑名单用的是同一个 key。
     * @param token 来自 Authorization 头（可能带 "Bearer " 前缀）
     */
    @Override
    public void logout(String token) {
        keyPairManager.blacklist(token);
    }

    /**
     * 根据用户名获取用户信息
     * @param userPageSearch 用户分页查询参数
     * @return 用户分页信息
     */
    @Override
    public IPage<UserSearchVO> getUserByUserNamePage(UserPageSearch userPageSearch) {
        // 创建分页对象
        Page<User> page = new Page<>(userPageSearch.getPageNum(), userPageSearch.getPageSize());
        // 根据用户名查询用户信息
        IPage<User> userIPage = lambdaQuery().like(User::getUsername, userPageSearch.getUsername()).page(page);
        // 转换用户信息为用户分页信息
        List<UserSearchVO> userSearchVOS = BeanUtil.copyToList(userIPage.getRecords(), UserSearchVO.class);
        log.info("userSearchVOS: {}", userSearchVOS);
        // 创建用户分页对象
        Page<UserSearchVO> pageVO = new Page<>(userPageSearch.getPageNum(), userPageSearch.getPageSize());
        // 设置用户分页信息
        pageVO.setRecords(userSearchVOS);
        return pageVO;
    }

    /**
     * 根据用户ID查询用户资料（不含密码）
     * @param userId 用户ID
     * @return 用户信息，不存在时返回 null
     */
    @Override
    public UserProfileVO searchUser(Long userId) {
        return toProfileVO(userId == null ? null : userMapper.selectById(userId));
    }

    /**
     * 修改当前登录用户的资料：邮箱按传入值更新，密码仅在 newPassword 非空时修改
     * @param userId 当前登录用户ID
     * @param userUpdateDTO 修改内容
     * @return 更新后的用户资料
     */
    @Override
    public Result<UserProfileVO> updateProfile(Long userId, UserUpdateDTO userUpdateDTO) {
        if (userUpdateDTO == null) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.EMPTY_PROFILE_UPDATE);
        }
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND, MsgConstant.USER_NOT_FOUND);
        }

        boolean emailChanged = false;
        boolean avatarChanged = false;
        boolean passwordChanged = false;

        if (userUpdateDTO.getEmail() != null) {
            String email = userUpdateDTO.getEmail().trim();
            if (email.length() > 255) {
                return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.EMAIL_TOO_LONG);
            }
            // 空串表示清空邮箱
            user.setEmail(email.isEmpty() ? null : email);
            emailChanged = true;
        }

        if (userUpdateDTO.getAvatar() != null) {
            String avatar = userUpdateDTO.getAvatar().trim();
            if (avatar.length() > 512) {
                return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.AVATAR_URL_TOO_LONG);
            }
            // 空串表示恢复默认头像
            user.setAvatar(avatar.isEmpty() ? null : avatar);
            avatarChanged = true;
        }

        String newPassword = userUpdateDTO.getNewPassword();
        if (StrUtil.isNotBlank(newPassword)) {
            if (StrUtil.isBlank(userUpdateDTO.getOldPassword())) {
                return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.OLD_PASSWORD_REQUIRED);
            }
            if (!bCryptPasswordEncoder.matches(userUpdateDTO.getOldPassword(), user.getPassword())) {
                return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.OLD_PASSWORD_INCORRECT);
            }
            if (newPassword.length() < 6 || newPassword.length() > 64) {
                return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.PASSWORD_LENGTH_INVALID);
            }
            user.setPassword(bCryptPasswordEncoder.encode(newPassword));
            passwordChanged = true;
        }

        if (!emailChanged && !avatarChanged && !passwordChanged) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.EMPTY_PROFILE_UPDATE);
        }

        LocalDateTime now = LocalDateTime.now();
        user.setUpdateTime(now);
        // updateById 默认忽略 null 字段，清空邮箱要显式 set
        LambdaUpdateWrapper<User> update = Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getUpdateTime, now);
        if (emailChanged) {
            update.set(User::getEmail, user.getEmail());
        }
        if (avatarChanged) {
            update.set(User::getAvatar, user.getAvatar());
        }
        if (passwordChanged) {
            update.set(User::getPassword, user.getPassword());
        }
        userMapper.update(null, update);
        return Result.ok(toProfileVO(user));
    }

    private UserProfileVO toProfileVO(User user) {
        if (user == null) {
            return null;
        }
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .createTime(user.getCreateTime())
                .updateTime(user.getUpdateTime())
                .build();
    }
}

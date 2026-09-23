package com.huahuo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huahuo.mapper.UserMapper;
import com.huahuo.message.MsgConstant;
import com.huahuo.service.UserService;
import dto.UserDTO;
import dto.UserLoginDTO;
import dto.UserPageSearch;
import dto.UserSearchVO;
import exception.LoginException;
import jwt.KeyPairManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import po.User;
import vo.UserLoginVO;

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
        // 登录成功
        return UserLoginVO.builder()
                .token(token)
                .userId(userList.get(0).getId())
                .username(userList.get(0).getUsername())
                .build();
    }

    /**
     * 用户登出
     * @param token 用户token
     */
    @Override
    public void logout(String token) {
        JWT jwt = JWTUtil.parseToken(token);
        Date exp = (Date) jwt.getPayload().getClaim("exp");
        // 计算token剩余有效期
        long remaining = exp.getTime() - System.currentTimeMillis();
        // 如果token还有剩余有效期，则加入黑名单
        if (remaining > 0) {
            stringRedisTemplate.opsForValue().set(
                    "jwt:blacklist:" + token,
                    "logout",
                    remaining,
                    TimeUnit.MILLISECONDS
            );
        }
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
     * 根据用户名查询用户信息
     * @param userId 用户ID
     * @return 用户信息
     */
    @Override
    public User searchUser(Long userId) {
        return userMapper.selectById(userId);
    }
}

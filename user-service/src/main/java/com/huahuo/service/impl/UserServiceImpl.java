package com.huahuo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huahuo.mapper.UserMapper;
import com.huahuo.service.UserService;
import org.springframework.stereotype.Service;
import po.User;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

}

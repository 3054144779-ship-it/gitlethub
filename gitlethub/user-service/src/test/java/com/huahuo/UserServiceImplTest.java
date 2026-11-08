package com.huahuo;

import com.huahuo.mapper.UserMapper;
import com.huahuo.service.UserService;
import dto.UserLoginDTO;
import exception.LoginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import po.User;
import vo.UserLoginVO;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
// 告诉 Spring 用 test 配置
@ActiveProfiles("test")
public class UserServiceImplTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @BeforeEach
    void setUp() {
        userMapper.delete(null); // 每个测试前清空表
        // 插入测试用户
        User user = new User();
        user.setUsername("alice");
        user.setPassword(bCryptPasswordEncoder.encode("correct"));
        userMapper.insert(user);
    }

    @Test
    void login_wrongPassword_throwsException() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("wrong");

        assertThrows(LoginException.class, () -> userService.login(dto));
    }

    @Test
    void login_correctPassword_returnsToken() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("correct");

        UserLoginVO vo = userService.login(dto);

        assertNotNull(vo.getToken());
        assertEquals("alice", vo.getUsername());
    }
}
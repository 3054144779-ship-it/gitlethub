package com.huahuo;

import common.Result;
import dto.UserDTO;
import dto.UserLoginDTO;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UserControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @Order(1)
    void registration_success() {
        UserDTO dto = new UserDTO();
        dto.setUsername("alice");
        dto.setPassword("123456");
        dto.setEmail("alice@test.com");

        ResponseEntity<Result> resp = restTemplate.postForEntity("/user/registration", dto, Result.class);

        System.out.println("=== 注册响应 ===");
        System.out.println("code: " + resp.getBody().getCode());
        System.out.println("message: " + resp.getBody().getMessage());
        assertEquals(0, resp.getBody().getCode());
    }

    @Test
    @Order(2)
    void login_correctPassword_returnsToken() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("123456");

        ResponseEntity<Result> resp = restTemplate.postForEntity("/user/login", dto, Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getCode());
        assertNotNull(resp.getBody().getData());
    }

    @Test
    @Order(3)
    void login_wrongPassword_throwsException() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("wrongpassword");

        ResponseEntity<Result> resp = restTemplate.postForEntity("/user/login", dto, Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotEquals(0, resp.getBody().getCode());
    }

    @Test
    @Order(4)
    void login_nonExistentUser_throwsException() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("nobody");
        dto.setPassword("123456");

        ResponseEntity<Result> resp = restTemplate.postForEntity("/user/login", dto, Result.class);

        assertNotEquals(0, resp.getBody().getCode());
    }

    @Test
    @Order(5)
    void searchUser_exists_returnsUser() {
        ResponseEntity<Result> resp = restTemplate.getForEntity("/user/1", Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getCode());
    }

    @Test
    @Order(6)
    void searchUser_notExists_returnsNull() {
        ResponseEntity<Result> resp = restTemplate.getForEntity("/user/9999", Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @Order(7)
    void getUserByUserNamePage_success() {
        ResponseEntity<Result> resp = restTemplate.getForEntity(
                "/user/username/list?username=alice&pageNum=1&pageSize=10", Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getCode());
    }
}
package org.openapitools;

import cn.hutool.jwt.JWT;
import jwt.KeyPairManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openapitools.filter.JwtAuthFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JwtAuthFilter 的单元测试：只验证"把谁的身份注入到 request"以及"什么样的 token 应当被忽略"。
 * 签名校验用 mock 顶替（真实密钥对在共享 Redis 里），token 本体用 hutool 真实生成以便走真实解析。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final long ONE_HOUR = 3600_000L;

    @Mock
    private KeyPairManager keyPairManager;

    @InjectMocks
    private JwtAuthFilter filter;

    /** 签名有效、未过期、未登出 → 注入 userId */
    @Test
    void validToken_injectsUserId() throws Exception {
        when(keyPairManager.verifyToken(anyString())).thenReturn(true);
        when(keyPairManager.isBlacklisted(anyString())).thenReturn(false);

        MockHttpServletRequest request = request(tokenWith(5L, System.currentTimeMillis() + ONE_HOUR));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(5L, request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
        assertNotNull(chain.getRequest(), "过滤器必须放行，不能拦截请求");
    }

    /** claim 可能是 Integer（JSON 小整数）→ 仍要转成 Long，否则 controller 的 @RequestAttribute Long 会绑不上 */
    @Test
    void integerClaim_isConvertedToLong() throws Exception {
        when(keyPairManager.verifyToken(anyString())).thenReturn(true);
        when(keyPairManager.isBlacklisted(anyString())).thenReturn(false);

        MockHttpServletRequest request = request(tokenWith(7, System.currentTimeMillis() + ONE_HOUR));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(7L, request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
    }

    @Test
    void missingAuthorizationHeader_staysAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
        assertNotNull(chain.getRequest());
    }

    @Test
    void nonBearerHeader_staysAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
        assertNotNull(chain.getRequest());
    }

    /** 签名不通过 → 匿名，且不应去查 Redis 黑名单（先验签名再查库） */
    @Test
    void invalidSignature_staysAnonymousWithoutRedisLookup() throws Exception {
        when(keyPairManager.verifyToken(anyString())).thenReturn(false);

        MockHttpServletRequest request = request(tokenWith(5L, System.currentTimeMillis() + ONE_HOUR));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
        verify(keyPairManager, never()).isBlacklisted(anyString());
        assertNotNull(chain.getRequest());
    }

    @Test
    void expiredToken_staysAnonymous() throws Exception {
        when(keyPairManager.verifyToken(anyString())).thenReturn(true);

        MockHttpServletRequest request = request(tokenWith(5L, System.currentTimeMillis() - 60_000));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
    }

    /** 已登出（在 Redis 黑名单里）的 token 不能再当身份用 */
    @Test
    void blacklistedToken_staysAnonymous() throws Exception {
        when(keyPairManager.verifyToken(anyString())).thenReturn(true);
        when(keyPairManager.isBlacklisted(anyString())).thenReturn(true);

        MockHttpServletRequest request = request(tokenWith(5L, System.currentTimeMillis() + ONE_HOUR));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
    }

    /** Redis 挂掉时鉴权降级为匿名，但绝不能阻断请求（否则公开读接口全废） */
    @Test
    void redisFailure_doesNotBreakRequestChain() throws Exception {
        when(keyPairManager.verifyToken(anyString())).thenReturn(true);
        when(keyPairManager.isBlacklisted(anyString())).thenThrow(new RuntimeException("redis down"));

        MockHttpServletRequest request = request(tokenWith(5L, System.currentTimeMillis() + ONE_HOUR));
        MockFilterChain chain = new MockFilterChain();

        assertDoesNotThrow(() -> filter.doFilter(request, new MockHttpServletResponse(), chain));

        assertNull(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE));
        assertNotNull(chain.getRequest());
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    /** 用 hutool 真实签出一个 token，payload 里带 userId 与 exp */
    private String tokenWith(Object userId, long expiresAtMillis) {
        return JWT.create()
                .setPayload("userId", userId)
                .setExpiresAt(new Date(expiresAtMillis))
                .setKey(KEY)
                .sign();
    }
}

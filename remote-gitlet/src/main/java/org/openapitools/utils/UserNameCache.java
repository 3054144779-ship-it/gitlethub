package org.openapitools.utils;

import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import po.User;

import java.time.Duration;

/**
 * userId -> username 的共享缓存。
 * 列表接口普遍存在「每行查一次用户表」的 N+1，同一个用户会被反复查询。
 */
@Slf4j
@Component
public class UserNameCache {

    private static final String KEY_PREFIX = "user:name:";
    private static final Integer TTL_SECONDS = 600;

    @Autowired
    private RedisCacheUtils redisCacheUtils;
    @Autowired
    private RemoteUserMapper remoteUserMapper;

    /** 用户名没有改名入口，缓存不会出现显示不一致；查不到不缓存，避免用户新建后长时间解析失败 */
    public String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        String key = KEY_PREFIX + userId;
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("用户名缓存读取失败，回源数据库，key={}", key, e);
        }
        User user = remoteUserMapper.selectById(userId);
        String username = user == null ? null : user.getUsername();
        if (username != null) {
            try {
                redisCacheUtils.set(key, username, Duration.ofSeconds(TTL_SECONDS));
            } catch (Exception e) {
                log.warn("用户名缓存写入失败，key={}", key, e);
            }
        }
        return username;
    }
}

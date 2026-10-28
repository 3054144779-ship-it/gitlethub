package org.openapitools.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis缓存工具类
 */
@Component
public class RedisCacheUtils {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    /** 带过期时间写入，缓存必须有 TTL，否则脏数据永久驻留 */
    public void set(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 原子自增，用于版本号这类「每次写都必须拿到新值」的计数。
     * 先 GET 再 SET(version+1) 在并发下会丢更新，两个写拿到同一个版本号。
     */
    public long increment(String key, Duration ttl) {
        Long value = stringRedisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            // 只在首次创建时设置过期，避免每次写都多一次 EXPIRE
            stringRedisTemplate.expire(key, ttl);
        }
        return value == null ? 0L : value;
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }
}

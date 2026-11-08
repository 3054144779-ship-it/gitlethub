package org.openapitools.utils;

import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
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

    private static final Integer TTL_SECONDS = 600;

    @Autowired
    private RemoteUserMapper remoteUserMapper;

    /** 用户名没有改名入口，缓存不会出现显示不一致；查不到不缓存，避免用户新建后长时间解析失败 */
    @Cacheable(
            value = "usernameCache",
            key = "#userId.toString",
            condition = "#userId != null", // 条件为空时，不缓存。
            unless = "#result == null"     // 方法返回 null 时，不要把 null 写进缓存。
    )
    public String usernameOf(Long userId) {
        User user = remoteUserMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }
}

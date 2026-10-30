package org.openapitools.configuration;

import com.alibaba.nacos.common.utils.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties props) {
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://" + props.getHost() + ":" + props.getPort())
                .setDatabase(props.getDatabase());
        if (StringUtils.hasText(props.getPassword())) {
            server.setPassword(props.getPassword());
        }
        return Redisson.create(config);
    }

}

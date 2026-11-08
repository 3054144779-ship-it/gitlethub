package org.openapitools.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "git.executor")
public class PoolsConfig {

    @Data
    public static class Pool {
        private int coreSize;  // 核心线程数
        private int maxSize;   // 最大线程数
        private int queueSize; // 队列大小

        public Pool() {}

        public Pool(int coreSize, int maxSize, int queueSize) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueSize = queueSize;
        }
    }

    public Pool pull = new Pool(4, 8, 20);
    public Pool push = new Pool(4, 4, 50);
    public Pool ai = new Pool(2, 4, 100);
    public Pool event = new Pool(2, 4, 10);
}

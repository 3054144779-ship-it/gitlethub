package org.openapitools.configuration;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    /**
     * 话题交换机
     * @return
     */
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange("gitlet.pr.exchange");
    }

    /**
     * PR话题队列
     * @return PR话题队列
     */
    @Bean
    public Queue topicQueue() {
        return QueueBuilder.durable("pr_merged_queue")
                // 路由失败时，转发到默认交换机的死信队列
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "pr_merged_dlq")
                .build();
    }

    /**
     * PR合并死信队列
     * @return PR合并死信队列
     */
    @Bean
    public Queue prMergeDlq() {
        return QueueBuilder.durable("pr_merged_dlq").build();
    }

    /**
     * 绑定话题队列和话题交换机
     * @param topicQueue 话题队列
     * @param topicExchange 话题交换机
     * @return 绑定关系
     */
    @Bean
    public Binding bindingTopicQueue(Queue topicQueue, TopicExchange topicExchange) {
        // 路由键
        return BindingBuilder.bind(topicQueue)
                .to(topicExchange)
                .with("pr.merged");
    }
}

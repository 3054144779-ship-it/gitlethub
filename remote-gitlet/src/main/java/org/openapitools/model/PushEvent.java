package org.openapitools.model;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Webhook事件器
 */
@Getter
public class PushEvent extends ApplicationEvent {
    private final Long repositoryId;
    private final String branch;
    private final String sha1;
    private final Long userId;
    private final boolean rejected;  // 是否被拒（受保护分支）

    /**
     * 构造方法：第一个参数 source 传给父类 ApplicationEvent
     * @param source 事件源（一般填 this，发布事件的那个bean）
     */
    public PushEvent(Object source, Long repositoryId, String branch, String sha1, Long userId, boolean rejected) {
        super(source);
        this.repositoryId = repositoryId;
        this.branch = branch;
        this.sha1 = sha1;
        this.userId = userId;
        this.rejected = rejected;
    }
}

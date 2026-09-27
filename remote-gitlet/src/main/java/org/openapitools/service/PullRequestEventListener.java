package org.openapitools.service;

import common.Result;
import dto.PullRequestDTO;
import org.openapitools.model.PushEvent;

public interface PullRequestEventListener {
    /**
     * 接收 push 事件
     * @param pushEvent push 事件
     */
    void onPush(PushEvent pushEvent);

    /**
     * 创建 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return pull request 的 url
     */
    Result<String> createPullRequest(String owner, String repo, PullRequestDTO pullRequestDTO);
}

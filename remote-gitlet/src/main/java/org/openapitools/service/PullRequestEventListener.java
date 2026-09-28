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

    /**
     * 合并 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 合并结果
     */
    Result<String> mergePullRequest(String owner, String repo, String pullRequestId);
}

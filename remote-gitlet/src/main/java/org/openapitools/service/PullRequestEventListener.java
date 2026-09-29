package org.openapitools.service;

import common.Result;
import dto.PullRequestDTO;
import org.openapitools.model.PushEvent;
import vo.PullRequestVO;

import java.util.List;

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

    /***
     * 获取仓库最近的一条 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 最近一条 PR；没有则 data 为 null
     */
    Result<PullRequestVO> getPullRequest(String owner, String repo);

    /**
     * 按 id 获取 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return pull request 详情
     */
    Result<PullRequestVO> getPullRequestById(String owner, String repo, String pullRequestId);

    /**
     * 列出仓库的 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param status  (optional) 状态：0 open / 1 closed / 2 merged；空表示全部
     * @param page  (optional) 页码，从 1 开始，缺省 1
     * @param size  (optional) 每页条数，缺省 20
     * @return pull request 列表
     */
    Result<List<PullRequestVO>> listPullRequests(String owner, String repo,
                                                 Integer status, Integer page, Integer size);

    /**
     * 关闭 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 关闭结果
     */
    Result<String> closePullRequest(String owner, String repo, String pullRequestId);
}

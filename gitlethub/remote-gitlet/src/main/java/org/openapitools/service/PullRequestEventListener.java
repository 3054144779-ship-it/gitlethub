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
     * @param pullRequestDTO  (required) 创建 Pull Request 的 DTO
     * @param actorId  (optional) 调用者用户 ID（由鉴权过滤器注入）；为空时回退为仓库所有者
     * @return pull request 的 url
     */
    Result<String> createPullRequest(String owner, String repo, PullRequestDTO pullRequestDTO, Long actorId);

    /**
     * 合并 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @param actorId  (optional) 调用者用户 ID；合并仅允许仓库 OWNER，为空视为无权
     * @return 合并结果
     */
    Result<String> mergePullRequest(String owner, String repo, String pullRequestId, Long actorId);

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
    Result<String> closePullRequest(String owner, String repo, String pullRequestId, Long actorId);
}

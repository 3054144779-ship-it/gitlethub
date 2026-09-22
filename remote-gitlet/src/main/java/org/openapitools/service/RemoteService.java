package org.openapitools.service;

import org.openapitools.model.*;

public interface RemoteService {
    /**
     * push 到远程仓库
     * 第一步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     */
    ApiReposOwnerRepoObjectsPost200Response pushObject(
            String owner, String repo, ObjectUploadRequest objectUploadRequest);

    /**
     * push 到远程仓库
     * 第二步：更新分支信息
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @param refUpdateRequest  (required) push 的内容
     * @return ApiReposOwnerRepoRefsBranchGet200Response
     */
    ApiReposOwnerRepoRefsBranchGet200Response pushUpdateBranch(
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest);

    /**
     * 创建当前用户的远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return ApiReposOwnerRepoObjectsPost200Response
     */
    ApiReposOwnerRepoObjectsPost200Response createRemoteRepository(String owner, String repo);

    /**
     * 读取分支指向
      * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @return ApiReposOwnerRepoRefsBranchGet200Response
     */
    ApiReposOwnerRepoRefsBranchGet200Response getBranchInformation(String owner, String repo, String branch);

    /**
     * 拉取远程仓库
      * @param owner  (required) 当前仓库的所有者
      * @param repo  (required) 当前仓库的名称
      * @param since  (required) 从哪个提交开始拉取
     * @return ApiReposOwnerRepoObjectsGet200Response
     */
    ApiReposOwnerRepoObjectsGet200Response pull(String owner, String repo, String since);

    /**
     * 克隆远程仓库
      * @param owner  (required) 当前仓库的所有者
      * @param repo  (required) 当前仓库的名称
      * @return ApiReposOwnerRepoObjectsGet200Response
     */
    ApiReposOwnerRepoObjectsGet200Response cloneRepository(String owner, String repo);
}

package org.openapitools.service;

import org.openapitools.model.ApiReposOwnerRepoObjectsPost200Response;
import org.openapitools.model.ApiReposOwnerRepoRefsBranchGet200Response;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.RefUpdateRequest;

public interface PushService {
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
}

package org.openapitools.controller;

import org.openapitools.model.ApiReposOwnerRepoObjectsGet200Response;
import org.openapitools.model.ApiReposOwnerRepoObjectsPost200Response;
import org.openapitools.model.ApiReposOwnerRepoRefsBranchGet200Response;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.RefUpdateRequest;


import org.openapitools.service.PushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.Optional;
import jakarta.annotation.Generated;

import javax.annotation.Nullable;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
@Controller
@RequestMapping("${openapi.gitlethub.base-path:}")
public class RemoteController implements RemoteControllerInterface {

    private final NativeWebRequest request;

    @Autowired
    public PushService pushService;


    @Autowired
    public RemoteController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }


    /**
     * pull 拉取对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param since 客户端已有的最新 commit sha1；空表示全新 clone (optional)
     * @return
     */
    @Override
    public ResponseEntity<ApiReposOwnerRepoObjectsGet200Response> apiReposOwnerRepoObjectsGet(String owner, String repo, @Nullable String since) {
        return RemoteControllerInterface.super.apiReposOwnerRepoObjectsGet(owner, repo, since);
    }

    /**
     * push 到远程仓库
     * 第一步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     * @return ResponseEntity&lt;ApiReposOwnerRepoObjectsPost200Response&gt;
     */
    @Override
    public ResponseEntity<ApiReposOwnerRepoObjectsPost200Response> apiReposOwnerRepoObjectsPost(String owner, String repo, ObjectUploadRequest objectUploadRequest) {
        ApiReposOwnerRepoObjectsPost200Response response = pushService.apiReposOwnerRepoObjectsPost(owner, repo, objectUploadRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取分支信息
      * @param owner  (required) 当前仓库的所有者
      * @param repo  (required) 当前仓库的名称
     * @param branch 分支名（如 master） (required)
     * @return ResponseEntity&lt;ApiReposOwnerRepoRefsBranchGet200Response&gt;
     */
    @Override
    public ResponseEntity<ApiReposOwnerRepoRefsBranchGet200Response> apiReposOwnerRepoRefsBranchGet(String owner, String repo, String branch) {
        return RemoteControllerInterface.super.apiReposOwnerRepoRefsBranchGet(owner, repo, branch);
    }

    /**
     * push 到远程仓库
     * 第二步：更新分支信息
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch 分支名（如 master） (required)
     * @param refUpdateRequest  (required)
     * @return ResponseEntity&lt;ApiReposOwnerRepoRefsBranchGet200Response&gt;
     */
    @Override
    public ResponseEntity<ApiReposOwnerRepoRefsBranchGet200Response> apiReposOwnerRepoRefsBranchPost(String owner, String repo, String branch, RefUpdateRequest refUpdateRequest) {
        ApiReposOwnerRepoRefsBranchGet200Response response = pushService.apiReposOwnerRepoRefsBranchPost(owner, repo, branch, refUpdateRequest);
        return RemoteControllerInterface.super.apiReposOwnerRepoRefsBranchPost(owner, repo, branch, refUpdateRequest);
    }
}

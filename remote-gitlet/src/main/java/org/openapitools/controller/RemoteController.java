package org.openapitools.controller;

import org.openapitools.model.ApiReposOwnerRepoObjectsGet200Response;
import org.openapitools.model.ApiReposOwnerRepoObjectsPost200Response;
import org.openapitools.model.ApiReposOwnerRepoRefsBranchGet200Response;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.RefUpdateRequest;


import org.openapitools.service.RemoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Generated;

import javax.annotation.Nullable;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
@Controller
@RequestMapping("/api/repos/{owner}/{repo}")
public class RemoteController {

    @Autowired
    private RemoteService remoteService;
    /**
     * pull 拉取对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param since 客户端已有的最新 commit sha1；空表示全新 clone (optional)
     * @return
     */
    @GetMapping("/objects")
    public ResponseEntity<ApiReposOwnerRepoObjectsGet200Response> pull(
            @PathVariable String owner, @PathVariable String repo, @Nullable @RequestParam("since") String since) {
        ApiReposOwnerRepoObjectsGet200Response response = remoteService.pull(owner, repo, since);
        return ResponseEntity.ok(response);
    }

    /**
     * push 到远程仓库
     * 第一步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     * @return ResponseEntity&lt;ApiReposOwnerRepoObjectsPost200Response&gt;
     */
    @PostMapping("/objects")
    public ResponseEntity<ApiReposOwnerRepoObjectsPost200Response> pushObject(
            @PathVariable String owner, @PathVariable String repo, @RequestBody ObjectUploadRequest objectUploadRequest) {
        ApiReposOwnerRepoObjectsPost200Response response =
                remoteService.pushObject(owner, repo, objectUploadRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * 读取分支指向
      * @param owner  (required) 当前仓库的所有者
      * @param repo  (required) 当前仓库的名称
     * @param branch 分支名（如 master） (required)
     * @return ResponseEntity&lt;ApiReposOwnerRepoRefsBranchGet200Response&gt;
     */
    @GetMapping("/refs/{branch}")
    public ResponseEntity<ApiReposOwnerRepoRefsBranchGet200Response> getBranchInformation(
            @PathVariable String owner, @PathVariable String repo, @PathVariable String branch) {
        ApiReposOwnerRepoRefsBranchGet200Response response =
                remoteService.getBranchInformation(owner, repo, branch);
        return ResponseEntity.ok(response);
    }

    /**
     * push 到远程仓库
     * 第二步：更新分支信息（TODO：加乐观锁）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch 分支名（如 master） (required)
     * @param refUpdateRequest  (required)
     * @return ResponseEntity&lt;ApiReposOwnerRepoRefsBranchGet200Response&gt;
     */
    @PostMapping("/refs/{branch}")
    public ResponseEntity<ApiReposOwnerRepoRefsBranchGet200Response> pushUpdateBranch(
                                                    @PathVariable String owner, @PathVariable String repo,
                                                    @PathVariable String branch, @RequestBody RefUpdateRequest refUpdateRequest) {
        ApiReposOwnerRepoRefsBranchGet200Response response =
                remoteService.pushUpdateBranch(owner, repo, branch, refUpdateRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * 创建当前用户的远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return ResponseEntity&lt;ApiReposOwnerRepoObjectsPost200Response&gt;
     */
    @PostMapping("/repo")
    public ResponseEntity<ApiReposOwnerRepoObjectsPost200Response> createRemoteRepository(
            @PathVariable String owner, @PathVariable String repo) {

        ApiReposOwnerRepoObjectsPost200Response response = remoteService.createRemoteRepository(owner, repo);
        return ResponseEntity.ok(response);
    }

    /**
     * clone 远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return ResponseEntity&lt;ApiReposOwnerRepoObjectsGet200Response&gt;
     */
    @GetMapping("/clone")
    public ResponseEntity<ApiReposOwnerRepoObjectsGet200Response> cloneRepository(
            @PathVariable String owner, @PathVariable String repo) {

        ApiReposOwnerRepoObjectsGet200Response response = remoteService.cloneRepository(owner, repo);
        return ResponseEntity.ok(response);
    }


}

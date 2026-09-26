package org.openapitools.controller;

import common.Result;
import bo.ObjectBundle;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;


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
     * @return 拉取到的对象列表
     */
    @GetMapping("/objects")
    public ResponseEntity<Result<ObjectBundle>> pull(
            @PathVariable String owner, @PathVariable String repo, @Nullable @RequestParam("since") String since) {
        Result<ObjectBundle> response = remoteService.pull(owner, repo, since);
        return ResponseEntity.ok(response);
    }

    /**
     * push 到远程仓库
     * 第一步（特殊）：强制更新分支信息（乐观锁）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @param refUpdateRequest  (required)  分支更新请求
     * @return 上传结果
     */
    @PostMapping("/refs/{branch}/force")
    public ResponseEntity<Result<RefView>> pushForceUpdateBranch(
            @PathVariable String owner, @PathVariable String repo,
            @PathVariable String branch,
            @RequestBody RefUpdateRequest refUpdateRequest) {
        Result<RefView> response =
                remoteService.pushForceUpdateBranch(owner, repo, branch, refUpdateRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * push 到远程仓库
     * 第一步：更新分支信息（乐观锁）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch 分支名（如 master） (required)
     * @param refUpdateRequest  (required)
     * @return 更新后的分支指向
     */
    @PostMapping("/refs/{branch}")
    public ResponseEntity<Result<RefView>> pushUpdateBranch(
            @PathVariable String owner, @PathVariable String repo,
            @PathVariable String branch,
            @RequestBody RefUpdateRequest refUpdateRequest) {
        Result<RefView> response =
                remoteService.pushUpdateBranch(owner, repo, branch, refUpdateRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * push 到远程仓库
     * 第二步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     * @return 上传结果
     */
    @PostMapping("/objects")
    public ResponseEntity<Result<ObjectUploadResult>> pushObject(
            @PathVariable String owner, @PathVariable String repo, @RequestBody ObjectUploadRequest objectUploadRequest) {
        Result<ObjectUploadResult> response =
                remoteService.pushObject(owner, repo, objectUploadRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * 读取分支指向
      * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch 分支名（如 master） (required)
     * @return 分支信息
     */
    @GetMapping("/refs/{branch}")
    public ResponseEntity<Result<RefView>> getBranchInformation(
            @PathVariable String owner, @PathVariable String repo, @PathVariable String branch) {
        Result<RefView> response =
                remoteService.getBranchInformation(owner, repo, branch);
        return ResponseEntity.ok(response);
    }


    // 建仓入口已独立到 RepositoryController（POST /api/repos），不在这里重复挂

    /**
     * clone 远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 仓库的全部对象
     */
    @GetMapping("/clone")
    public ResponseEntity<Result<ObjectBundle>> cloneRepository(
            @PathVariable String owner, @PathVariable String repo) {

        Result<ObjectBundle> response = remoteService.cloneRepository(owner, repo);
        return ResponseEntity.ok(response);
    }

}

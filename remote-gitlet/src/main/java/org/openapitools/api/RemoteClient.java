package org.openapitools.api;

import common.Result;
import org.openapitools.model.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Nullable;

@FeignClient(name = "remote-gitlet")
@RequestMapping("/api/repos/{owner}/{repo}")
public interface RemoteClient {
    /**
     * pull 拉取对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param since 客户端已有的最新 commit sha1；空表示全新 clone (optional)
     * @return 拉取到的对象列表
     */
    @GetMapping("/objects")
    public ResponseEntity<Result<ObjectBundle>> pull(
            @PathVariable String owner, @PathVariable String repo, @Nullable @RequestParam("since") String since);

    /**
     * push 到远程仓库
     * 第一步（特殊）：强制更新分支信息
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
            @RequestBody RefUpdateRequest refUpdateRequest);

    /**
     * push 到远程仓库
     * 第一步：更新分支信息
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
            @RequestBody RefUpdateRequest refUpdateRequest);

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
            @PathVariable String owner, @PathVariable String repo,
            @RequestBody ObjectUploadRequest objectUploadRequest);


    /**
     * clone 远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 仓库的全部对象
     */
    @GetMapping("/clone")
    public ResponseEntity<Result<ObjectBundle>> cloneRepository(
            @PathVariable String owner, @PathVariable String repo);
}

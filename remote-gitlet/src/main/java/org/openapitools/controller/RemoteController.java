package org.openapitools.controller;

import common.Result;
import bo.ObjectBundle;
import dto.PullRequestDTO;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;


import org.openapitools.service.PullRequestEventListener;
import org.openapitools.service.RemoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Generated;
import po.BranchPointer;
import vo.BlobVO;
import vo.CommitVO;
import vo.FileChangeVO;
import vo.FileEntryVO;
import vo.PullRequestVO;

import javax.annotation.Nullable;
import java.util.List;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
@Controller
@RequestMapping("/api/repos/{owner}/{repo}")
public class RemoteController {

    @Autowired
    private RemoteService remoteService;
    @Autowired
    private PullRequestEventListener pullRequestEventListener;

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

    /**
     * 创建 pull request
      * @param owner  (required) 当前仓库的所有者
      * @param repo  (required) 当前仓库的名称
      * @return  (required) pull request 的 url
     */
    @PostMapping("/pull-request")
    public ResponseEntity<Result<String>> createPullRequest(
            @PathVariable String owner, @PathVariable String repo, @RequestBody PullRequestDTO pullRequestDTO) {
        Result<String> response = pullRequestEventListener.createPullRequest(owner, repo, pullRequestDTO);
        return ResponseEntity.ok(response);
    }

    /**
     * 合并 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 合并响应结果
     */
    @PostMapping("/pull-request/{pullRequestId}")
    public ResponseEntity<Result<String>> mergePullRequest(
            @PathVariable String owner, @PathVariable String repo, @PathVariable String pullRequestId) {
        Result<String> response = pullRequestEventListener.mergePullRequest(owner, repo, pullRequestId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pull-request")
    public ResponseEntity<Result<PullRequestVO>> getPullRequest(
            @PathVariable String owner, @PathVariable String repo) {
        Result<PullRequestVO> response = pullRequestEventListener.getPullRequest(owner, repo);
        return ResponseEntity.ok(response);
    }

    /**
     * 查看单个 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return pull request 详情
     */
    @GetMapping("/pull-request/{pullRequestId}")
    public ResponseEntity<Result<PullRequestVO>> getPullRequestById(
            @PathVariable String owner, @PathVariable String repo,
            @PathVariable String pullRequestId) {
        Result<PullRequestVO> response =
                pullRequestEventListener.getPullRequestById(owner, repo, pullRequestId);
        return ResponseEntity.ok(response);
    }

    /**
     * 列出仓库的 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param status  (optional) 0 open / 1 closed / 2 merged；空表示全部
     * @param page  (optional) 页码，从 1 开始
     * @param size  (optional) 每页条数
     * @return pull request 列表
     */
    @GetMapping("/pull-requests")
    public ResponseEntity<Result<List<PullRequestVO>>> listPullRequests(
            @PathVariable String owner, @PathVariable String repo,
            @Nullable @RequestParam(value = "status", required = false) Integer status,
            @Nullable @RequestParam(value = "page", required = false) Integer page,
            @Nullable @RequestParam(value = "size", required = false) Integer size) {
        Result<List<PullRequestVO>> response =
                pullRequestEventListener.listPullRequests(owner, repo, status, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 关闭 pull request
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param pullRequestId  (required) pull request 的 id
     * @return 关闭结果
     */
    @PostMapping("/pull-request/{pullRequestId}/close")
    public ResponseEntity<Result<String>> closePullRequest(
            @PathVariable String owner, @PathVariable String repo,
            @PathVariable String pullRequestId) {
        Result<String> response =
                pullRequestEventListener.closePullRequest(owner, repo, pullRequestId);
        return ResponseEntity.ok(response);
    }

    /**
     * 列出仓库的所有分支
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 分支列表
     */
    @GetMapping("/branches")
    public ResponseEntity<Result<List<BranchPointer>>> listBranches(
            @PathVariable String owner, @PathVariable String repo) {
        return ResponseEntity.ok(remoteService.listBranches(owner, repo));
    }

    /**
     * 列出某个分支的提交历史
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (optional) 分支名，缺省用仓库默认分支
     * @param page  (optional) 页码，从 1 开始
     * @param size  (optional) 每页条数
     * @return 提交列表
     */
    @GetMapping("/commits")
    public ResponseEntity<Result<List<CommitVO>>> listCommits(
            @PathVariable String owner, @PathVariable String repo,
            @Nullable @RequestParam(value = "branch", required = false) String branch,
            @Nullable @RequestParam(value = "page", required = false) Integer page,
            @Nullable @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(remoteService.listCommits(owner, repo, branch, page, size));
    }

    /**
     * 查看单个提交
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param sha  (required) 提交 SHA
     * @return 提交详情
     */
    @GetMapping("/commits/{sha}")
    public ResponseEntity<Result<CommitVO>> getCommit(
            @PathVariable String owner, @PathVariable String repo, @PathVariable String sha) {
        return ResponseEntity.ok(remoteService.getCommit(owner, repo, sha));
    }

    /**
     * 查看提交与其第一父提交的差异
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param sha  (required) 提交 SHA
     * @return 文件变更列表
     */
    @GetMapping("/commits/{sha}/diff")
    public ResponseEntity<Result<List<FileChangeVO>>> diffCommit(
            @PathVariable String owner, @PathVariable String repo, @PathVariable String sha) {
        return ResponseEntity.ok(remoteService.diffCommit(owner, repo, sha));
    }

    /**
     * 列出某个提交（或分支）对应的文件树
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param ref  (required) 提交 SHA 或分支名
     * @return 文件条目列表
     */
    @GetMapping("/tree")
    public ResponseEntity<Result<List<FileEntryVO>>> listTree(
            @PathVariable String owner, @PathVariable String repo,
            @RequestParam("ref") String ref) {
        return ResponseEntity.ok(remoteService.listTree(owner, repo, ref));
    }

    /**
     * 读取某个文件的内容
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param ref  (required) 提交 SHA 或分支名
     * @param path  (required) 文件路径
     * @return 文件内容
     */
    @GetMapping("/blob")
    public ResponseEntity<Result<BlobVO>> getBlob(
            @PathVariable String owner, @PathVariable String repo,
            @RequestParam("ref") String ref,
            @RequestParam("path") String path) {
        return ResponseEntity.ok(remoteService.getBlob(owner, repo, ref, path));
    }

}

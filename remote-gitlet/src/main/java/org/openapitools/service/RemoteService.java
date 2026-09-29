package org.openapitools.service;

import common.Result;
import dto.PullRequestDTO;
import dto.RepositoryCreateDTO;
import bo.ObjectBundle;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;
import po.BranchPointer;
import vo.BlobVO;
import vo.CommitVO;
import vo.FileChangeVO;
import vo.FileEntryVO;

import java.util.List;

public interface RemoteService {
    /**
     * push 到远程仓库
     * 第一步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     * @return 上传结果
     */
    Result<ObjectUploadResult> pushObject(
            String owner, String repo, ObjectUploadRequest objectUploadRequest);

    /**
     * push 到远程仓库
     * 第二步：更新分支信息
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @param refUpdateRequest  (required) push 的内容
     * @return 更新后的分支指向
     */
    Result<RefView> pushUpdateBranch(
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest);

    /**
     * 创建当前用户的远程仓库
     * @param repositoryCreateDTO  (required) 创建仓库的信息
     * @return 创建结果
     */
    Result<ObjectUploadResult> createRemoteRepository(RepositoryCreateDTO repositoryCreateDTO);

    /**
     * 读取分支指向
      * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @return 分支信息
     */
    Result<RefView> getBranchInformation(String owner, String repo, String branch);

    /**
     * 拉取远程仓库
      * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param since  (required) 从哪个提交开始拉取
     * @return 拉取的对象列表
     */
    Result<ObjectBundle> pull(String owner, String repo, String since);

    /**
     * 克隆远程仓库
      * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 仓库的全部对象
     */
    Result<ObjectBundle> cloneRepository(String owner, String repo);

    /**
     * 强制更新分支信息
      * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @param refUpdateRequest  (required) push 的内容
     * @return 更新后的分支指向
     */
    Result<RefView> pushForceUpdateBranch(String owner, String repo,
                                                 String branch, RefUpdateRequest refUpdateRequest);

    /**
     * 列出仓库的所有分支
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 分支列表
     */
    Result<List<BranchPointer>> listBranches(String owner, String repo);

    /**
     * 列出某个分支的提交历史（按父链倒序，合并 DAG 去重）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (optional) 分支名，缺省用仓库默认分支
     * @param page  (optional) 页码，从 1 开始，缺省 1
     * @param size  (optional) 每页条数，缺省 20
     * @return 提交列表（不含 trackedFiles/mergeFiles）
     */
    Result<List<CommitVO>> listCommits(String owner, String repo,
                                       String branch, Integer page, Integer size);

    /**
     * 查看单个提交的详情
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param sha  (required) 提交 SHA
     * @return 提交详情（含 trackedFiles/mergeFiles）
     */
    Result<CommitVO> getCommit(String owner, String repo, String sha);

    /**
     * 比较某个提交与其第一父提交的差异
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param sha  (required) 提交 SHA
     * @return 文件变更列表
     */
    Result<List<FileChangeVO>> diffCommit(String owner, String repo, String sha);

    /**
     * 列出某个提交（或分支）对应的文件树（扁平 path -> blobSha）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param ref  (required) 提交 SHA 或分支名
     * @return 文件条目列表
     */
    Result<List<FileEntryVO>> listTree(String owner, String repo, String ref);

    /**
     * 读取某个文件的内容
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param ref  (required) 提交 SHA 或分支名
     * @param path  (required) 文件路径
     * @return 文件内容（二进制时 content 为空）
     */
    Result<BlobVO> getBlob(String owner, String repo, String ref, String path);

}

package org.openapitools.service;

import common.Result;
import dto.RepositoryCreateDTO;
import bo.ObjectBundle;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;

/**
 * 仓库的写操作与对象传输（push / pull / clone / 建仓）。
 * 只读查询拆到 {@link BranchQueryService} / {@link CommitQueryService} / {@link BlobQueryService}。
 */
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
            String owner, String repo, ObjectUploadRequest objectUploadRequest, Long actorId);

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
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest, Long actorId);

    /**
     * 创建当前用户的远程仓库
     * @param userId  (required) 调用者ID（来自验签注入的 JWT claim），仓库所有者以此为准
     * @param repositoryCreateDTO  (required) 创建仓库的信息
     * @return 创建结果
     */
    Result<ObjectUploadResult> createRemoteRepository(Long userId, RepositoryCreateDTO repositoryCreateDTO);

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
                                                 String branch, RefUpdateRequest refUpdateRequest, Long actorId);

}

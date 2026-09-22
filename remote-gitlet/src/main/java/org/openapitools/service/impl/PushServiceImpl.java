package org.openapitools.service.impl;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.*;
import org.openapitools.service.PushService;
import org.openapitools.utils.Utils;
import org.openapitools.verification.BranchVerification;
import org.openapitools.verification.RepositoryVerification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;


@Slf4j
@Service
public class PushServiceImpl implements PushService {

    // TODO：此处应为仓库的根路径，确保仓库路径正确（默认为程序当前目录）
    @Value("${storage.base-path:${user.dir}}")
    private String basePath;


    /**
     * push 到远程仓库
     * 第一步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     */
    @Override
    public ApiReposOwnerRepoObjectsPost200Response pushObject(
            String owner, String repo, ObjectUploadRequest objectUploadRequest) {

        if (objectUploadRequest == null) {
            return new ApiReposOwnerRepoObjectsPost200Response()
                    .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_30002)
                    .message(MsgConstant.THE_SUBMITTED_FILE_CANNOT_BE_EMPTY);
        }

        // 创建仓库路径对象
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));

        // 检查仓库是否存在(不存在则报错)
        if (!RepositoryVerification.repositoryExists(repositoryPaths)) {
            log.info("仓库 {}/{} 不存在，创建中", owner, repo);
            return new ApiReposOwnerRepoObjectsPost200Response()
                    .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_30001)
                    .message(MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }

        // 获取当前所有对象
        List<@Valid GitletObject> objects = objectUploadRequest.getObjects();
        log.info("上传对象 {}", objects);
        // 读取仓库中所有的文件，检查是否有文件相同
        List<String> blobNames = Utils.plainFilenamesIn(repositoryPaths.getObject());
        List<String> commitNames = Utils.plainFilenamesIn(repositoryPaths.getCommits());

        for (GitletObject object : objects) {
            // 检查对象是否已存在
            boolean exists;
            if (object.getType() == GitletObject.TypeEnum.COMMIT) {
                log.info("检查commit {} 是否存在", object.getSha1());
                exists = commitNames != null && commitNames.contains(object.getSha1());
            } else {
                log.info("检查Blob {} 是否存在", object.getSha1());
                exists = blobNames != null && blobNames.contains(object.getSha1());
            }

            if (exists) {
                log.info("对象 {} 已存在，跳过上传", object.getSha1());
                continue;
            }

            File target;

            if (object.getType() == GitletObject.TypeEnum.COMMIT) {
                log.info("上传提交 {}, 我是一个Commit", object.getSha1());
                // 如果当前是一个新的提交
                target = Utils.join(repositoryPaths.getCommits(), object.getSha1());
            } else {
                log.info("上传提交 {}, 我是一个Blob", object.getSha1());
                // 如果当前是一个新的 blob
                target = Utils.join(repositoryPaths.getObject(), object.getSha1());
            }
            Utils.writeContents(target, (Object) object.getContent());
            log.debug("写入对象: type={}, sha1={}", object.getType(), object.getSha1());
        }
        return new ApiReposOwnerRepoObjectsPost200Response()
                .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_0).message(MsgConstant.OK);
    }

    /**
     * push 到远程仓库
     * 第二步：更新分支信息
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @param refUpdateRequest  (required) 提交之后远程 commit 的SHA1值
     * @return
     */
    @Override
    public ApiReposOwnerRepoRefsBranchGet200Response pushUpdateBranch(
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest) {

        log.info("更新分支 {} {}", branch, refUpdateRequest);
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));

        // 检查分支是否存在, 如果不存在则创建
        File currentBranch = BranchVerification.branchExists(repositoryPaths, branch);

        // 更新当前分支指向的提交
        Utils.writeContents(currentBranch, refUpdateRequest.getSha());
        // 此时不再更新头指针指向的分支，一直指向默认分支即可

        return new ApiReposOwnerRepoRefsBranchGet200Response()
                .code(ApiReposOwnerRepoRefsBranchGet200Response.CodeEnum.NUMBER_0).message(MsgConstant.OK);
    }

    /**
     * 创建远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return
     */
    @Override
    public ApiReposOwnerRepoObjectsPost200Response createRemoteRepository(String owner, String repo) {
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        // 校验仓库是否存在
        if (RepositoryVerification.repositoryExists(repositoryPaths)) {
            // 仓库存在则禁止重复创建
            return new ApiReposOwnerRepoObjectsPost200Response()
                    .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_30003)
                    .message(MsgConstant.THE_WAREHOUSE_ALREADY_EXISTS);
        }

        // 如果仓库不存在则创建仓库
        RepositoryVerification.createRepository(repositoryPaths);

        return new ApiReposOwnerRepoObjectsPost200Response()
                .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_0).message(MsgConstant.OK);
    }

    /**
     * 读取分支指向（为了后续做增量同步）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @return 分支信息
     */
    @Override
    public ApiReposOwnerRepoRefsBranchGet200Response getBranchInformation(String owner, String repo, String branch) {
        log.info("获取 {} / {} 的 {} 分支指向", owner, repo, branch);
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        // 当前分支文件
        File branchFile = Utils.join(repositoryPaths.getBranchDir(), branch);
        // 判断当前分支是否存在，如果不存在则返回错误码30004
        if (!branchFile.exists()) {
            return new ApiReposOwnerRepoRefsBranchGet200Response()
                    .code(ApiReposOwnerRepoRefsBranchGet200Response.CodeEnum.NUMBER_30004)
                    .message(MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }

        // 如果存在则返回 分支所指向的提交
        return new ApiReposOwnerRepoRefsBranchGet200Response()
                .code(ApiReposOwnerRepoRefsBranchGet200Response.CodeEnum.NUMBER_0)
                .message(MsgConstant.OK)
                .data(new RefView().branch(branch).sha(Utils.readContentsAsString(branchFile)));
    }
}

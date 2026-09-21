package org.openapitools.service.impl;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.model.*;
import org.openapitools.service.PushService;
import org.openapitools.utils.Utils;
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
    public ApiReposOwnerRepoObjectsPost200Response apiReposOwnerRepoObjectsPost(
            String owner, String repo, ObjectUploadRequest objectUploadRequest) {

        // 创建仓库路径对象
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));

        // 检查仓库是否存在
        if (!repositoryPaths.getRepositoryId().exists()) {
            log.info("仓库 {}/{} 不存在，创建中", owner, repo);
            repositoryPaths.getRepositoryId().mkdirs();
            repositoryPaths.getObject().mkdirs();
            repositoryPaths.getCommits().mkdirs();
            repositoryPaths.getBranchDir().mkdirs();
        }

        // 获取当前所有对象
        List<@Valid GitletObject> objects = objectUploadRequest.getObjects();
        log.info("上传对象 {}", objects);
        // 读取仓库中所有的文件，检查是否有文件相同
        List<String> blobNames = Utils.plainFilenamesIn(repositoryPaths.getObject());
        List<String> commitNames = Utils.plainFilenamesIn(repositoryPaths.getCommits());

        // 检查提交的文件是否为空
        if ((blobNames == null || blobNames.isEmpty()) && (commitNames == null || commitNames.isEmpty())) {
            log.debug("提交的文件不能为空");
            return new ApiReposOwnerRepoObjectsPost200Response()
                    .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_30001).message("提交的文件不能为空");
        }

        for (GitletObject object : objects) {
            // 检查对象是否已存在
            boolean exists;
            if (object.getType() == GitletObject.TypeEnum.COMMIT) {
                exists = commitNames != null && commitNames.contains(object.getSha1());
            } else {
                exists = blobNames != null && blobNames.contains(object.getSha1());
            }

            if (exists) {
                log.info("对象 {} 已存在，跳过上传", object.getSha1());
                continue;
            }

            File target;

            if (object.getType() == GitletObject.TypeEnum.COMMIT) {
                // 如果当前是一个新的提交
                target = Utils.join(repositoryPaths.getCommits(), object.getSha1());
            } else {
                // 如果当前是一个新的 blob
                target = Utils.join(repositoryPaths.getObject(), object.getSha1());
            }
            Utils.writeContents(target, (Object) object.getContent());
            log.debug("写入对象: type={}, sha1={}", object.getType(), object.getSha1());
        }
        return new ApiReposOwnerRepoObjectsPost200Response()
                .code(ApiReposOwnerRepoObjectsPost200Response.CodeEnum.NUMBER_0).message("ok");
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
    public ApiReposOwnerRepoRefsBranchGet200Response apiReposOwnerRepoRefsBranchPost(
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest) {

        log.info("更新分支 {} {}", branch, refUpdateRequest);
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        File currentBranch = Utils.join(repositoryPaths.getBranchDir(), branch);

        if (!currentBranch.exists()) {
            log.info("分支 {} 不存在，创建新分支", branch);
            currentBranch.getParentFile().mkdirs();
        }

        // 更新当前分支指向的提交
        Utils.writeContents(currentBranch, refUpdateRequest.getSha());

        return new ApiReposOwnerRepoRefsBranchGet200Response()
                .code(ApiReposOwnerRepoRefsBranchGet200Response.CodeEnum.NUMBER_0).message("ok");
    }
}

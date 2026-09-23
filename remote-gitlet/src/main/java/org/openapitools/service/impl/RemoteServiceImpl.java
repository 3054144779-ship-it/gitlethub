package org.openapitools.service.impl;

import cn.hutool.core.collection.CollUtil;
import common.Result;
import common.ResultCode;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.*;
import org.openapitools.service.RemoteService;
import org.openapitools.utils.Utils;
import org.openapitools.verification.BranchVerification;
import org.openapitools.verification.RepositoryVerification;
import po.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
public class RemoteServiceImpl implements RemoteService {

    // TODO：此处应为仓库的根路径，确保仓库路径正确（默认为程序当前目录）
    @Value("${storage.base-path:${user.dir}}")
    private String basePath;

    // 远程用户Mapper
    @Autowired
    private RemoteUserMapper remoteUserMapper;

    // 远程仓库Mapper
    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;

    /**
     * push 到远程仓库
     * 第一步：上传对象
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param objectUploadRequest  (required) push 的内容
     */
    @Override
    public Result<ObjectUploadResult> pushObject(
            String owner, String repo, ObjectUploadRequest objectUploadRequest) {

        if (objectUploadRequest == null) {
            // 沿用原 Post200Response 枚举里标注为「提交的文件不能为空」的 30002
            return Result.fail(ResultCode.BRANCH_NOT_FOUND, MsgConstant.THE_SUBMITTED_FILE_CANNOT_BE_EMPTY);
        }

        // 创建仓库路径对象
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));

        // 检查仓库是否存在(不存在则报错)
        if (!RepositoryVerification.repositoryExists(repositoryPaths)) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
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
        return Result.ok();
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
    public Result<RefView> pushUpdateBranch(
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest) {

        log.info("更新分支 {} {}", branch, refUpdateRequest);
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));

        // 检查分支是否存在, 如果不存在则创建
        File currentBranch = BranchVerification.branchExists(repositoryPaths, branch);

        // 更新当前分支指向的提交
        Utils.writeContents(currentBranch, refUpdateRequest.getSha());
        // 此时不再更新头指针指向的分支，一直指向默认分支即可

        return Result.ok();
    }

    /**
     * 创建远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return
     */
    @Override
    public Result<ObjectUploadResult> createRemoteRepository(String owner, String repo) {
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        // 校验仓库是否存在
        if (RepositoryVerification.repositoryExists(repositoryPaths)) {
            // 仓库存在则禁止重复创建
            return Result.fail(ResultCode.REPOSITORY_ALREADY_EXISTS, MsgConstant.THE_WAREHOUSE_ALREADY_EXISTS);
        }

        // 如果仓库不存在则创建仓库
        RepositoryVerification.createRepository(repositoryPaths);

        // 获取当前用户ID(根据用户名字查)
        Long ownerId = remoteUserMapper.getUserId(owner);

        // 创建仓库对象
        Repository repository = Repository.builder()
                .ownerId(ownerId)
                .name(repo)
                .description("")
                .visibility(0)
                .defaultBranch(MsgConstant.MAIN_BRANCH)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();
        // 存入到数据库
        remoteRepositoryMapper.insert(repository);

        return Result.ok();
    }

    /**
     * 读取分支指向（为了后续做增量同步）
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @return 分支信息
     */
    @Override
    public Result<RefView> getBranchInformation(String owner, String repo, String branch) {
        log.info("获取 {} / {} 的 {} 分支指向", owner, repo, branch);
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        // 当前分支文件
        File branchFile = Utils.join(repositoryPaths.getBranchDir(), branch);
        // 判断当前分支是否存在，如果不存在则返回错误码30004
        if (!branchFile.exists()) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }

        // 如果存在则返回 分支所指向的提交
        return Result.ok(new RefView().branch(branch).sha(Utils.readContentsAsString(branchFile)));
    }

    /**
     * 拉取远程仓库
     * TODO：全量拉取（临时）后续需要改为增量拉取
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param since  (required) 从哪个提交开始拉取
     * @return 拉取的提交列表
     */
    @Override
    public Result<ObjectBundle> pull(String owner, String repo, String since) {
        log.info("拉取 {} / {} 从 {} 开始的提交", owner, repo, since);
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));

        // 检查仓库是否存在(不存在则报错)
        if (!RepositoryVerification.repositoryExists(repositoryPaths)) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }

        // 读取仓库中所有的提交(存的是提交SHA1值)
        List<String> commitNames = Utils.plainFilenamesIn(repositoryPaths.getCommits());
        // 读取仓库中所有的Blobs(存的是BlobHA1值)
        List<String> blobNames = Utils.plainFilenamesIn(repositoryPaths.getObject());
        // 提交不能为空
        if (CollUtil.isEmpty(commitNames) || CollUtil.isEmpty(blobNames)) {
            log.info("仓库 {} / {} 中没有提交", owner, repo);
            return Result.fail(ResultCode.EMPTY_REPOSITORY, MsgConstant.PULL_FAILED);
        }

        // 提交列表
        List<GitletObject> commitList = commitNames.stream()
                // 读取仓库中的所有提交的内容（byte数组），byte数组支持的文件类型更多，String只能传入文本，只不过会base64编码大小会膨胀33%
                .map(commit -> new GitletObject()
                        .type(GitletObject.TypeEnum.COMMIT)
                        // 根据SHA1值读取内容
                        .content(Utils.readContents(Utils.join(repositoryPaths.getCommits(), commit)))
                        .sha1(commit))
                .collect(Collectors.toList());

        // Blob列表
        List<GitletObject> blobList = blobNames.stream()
                .map(blob -> new GitletObject()
                        .type(GitletObject.TypeEnum.BLOB)
                        .content(Utils.readContents(Utils.join(repositoryPaths.getObject(), blob)))
                        .sha1(blob))
                .collect(Collectors.toList());

        log.info("拉取的提交列表 {}", commitList);
        log.info("拉取的Blob列表 {}", blobList);

        List<GitletObject> fileList = CollUtil.newArrayList();
        fileList.addAll(commitList);
        fileList.addAll(blobList);

        log.info("拉取的文件列表 {}", fileList);

        return Result.ok(new ObjectBundle().objects(fileList));
    }

    /**
     * 克隆远程仓库
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return
     */
    @Override
    public Result<ObjectBundle> cloneRepository(String owner, String repo) {
        log.info("克隆 {} / {}", owner, repo);
        // TODO：后续将这里（since）改为第一个提交
        return this.pull(owner, repo, null);
    }
}

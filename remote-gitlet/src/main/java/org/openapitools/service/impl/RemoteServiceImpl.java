package org.openapitools.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import common.Result;
import common.ResultCode;
import dto.PullRequestDTO;
import dto.RepositoryCreateDTO;
import enums.CollaboratorLevelEnum;
import gitlet.commit.Commit;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.*;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.*;
import org.openapitools.service.RemoteService;
import org.openapitools.utils.Utils;
import org.openapitools.verification.BranchVerification;
import org.openapitools.verification.PermissionValidation;
import org.openapitools.verification.RepositoryVerification;
import org.openapitools.verification.VersionStatusVerification;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import po.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import bo.ObjectBundle;
import bo.GitletObject;
import vo.BlobVO;
import vo.CommitVO;
import vo.FileChangeVO;
import vo.FileEntryVO;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
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

    // 远程分支指针
    @Autowired
    private RemoteBranchPointerMapper remoteBranchPointer;

    // 远程仓库协作者
    @Autowired
    private RemoteCollaboratorMapper remoteCollaborator;

    // 远程提交指针
    @Autowired
    private RemoteCommitPointerMapper remoteCommitPointer;

    // 远程提交记录
    @Autowired
    private RemoteSubmissionRecordMapper remoteSubmissionRecordMapper;

    // 应用程序事件发布器
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;

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
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_SUBMITTED_FILE_CANNOT_BE_EMPTY);
        }

        // 获取用户id
        Long userId = remoteUserMapper.getUserId(owner);
        // 获取仓库id
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(userId, repo);
        // 获取提交者信息
        Collaborator collaborator = remoteCollaborator.getCollaborator(userId, repositoryId);

        // 检查用户权限
        if (!PermissionValidation.identityVerification(collaborator)) {
            log.warn("用户 {} 没有权限更新分支", owner);
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
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
    @Transactional(rollbackFor = Exception.class)
    public Result<RefView> pushUpdateBranch(
            String owner, String repo, String branch, RefUpdateRequest refUpdateRequest) {

        log.info("更新分支 {} {}", branch, refUpdateRequest);

        String sha1 = refUpdateRequest.getSha();

        // 检查该 commit 是否已存在于对象存储（防止分支指向一个未上传的对象）
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        List<String> commits = Utils.plainFilenamesIn(repositoryPaths.getCommits());
        // 检查当前SHA1是否已存在在对象中
        if (commits == null || !commits.contains(sha1)) {
            log.warn("提交 {} 不存在于对象储存", sha1);
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }

        // 获取用户id
        Long userId = remoteUserMapper.getUserId(owner);
        // 获取仓库id
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(userId, repo);
        // 获取分支指针
        BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, branch);
        // 获取提交者信息
        Collaborator collaborator = remoteCollaborator.getCollaborator(userId, repositoryId);
        log.info("userId:{}, repositoryId:{}", userId, repositoryId);

        // 检查用户权限
        if (!PermissionValidation.identityVerification(collaborator)) {
            log.warn("用户 {} 没有权限更新分支 {}", owner, branch);
//            log.info("协作者信息：{}", collaborator.toString());
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }

        // 分支不存在则创建：支持推送到新分支（如 dev/feature），不再只限于建库时的 master。
        // 放在权限校验之后（无权用户不能建分支）、非快进校验之前（保证 branchPointer 非 null）。
        if (branchPointer == null) {
            branchPointer = BranchPointer.builder()
                    .repositoryId(repositoryId)
                    .branchName(branch)
                    .commitSha(null)              // 新分支暂无指向，随后 pushWrite 会写入本次 sha
                    .isDefault(0)                 // 非默认分支
                    .isProtected(0)               // 新分支默认不受保护，可直推
                    .version(0)                   // 乐观锁版本从 0 开始
                    .updateTime(LocalDateTime.now())
                    .isDeleted(0)
                    .isForcePush(0)
                    .build();
            remoteBranchPointer.insert(branchPointer);   // insert 后 MyBatis-Plus 回填自增 id
            log.info("分支 {} 不存在，已创建新分支指针 id={}", branch, branchPointer.getId());
        }

        // 客户端非快进校验：若传了期望的旧指向，必须与服务端当前指向一致
        if (!VersionStatusVerification.nonFastForwardCheck(refUpdateRequest, branchPointer)) {
            return Result.fail(ResultCode.VERSION_CONFLICT, MsgConstant.VERSION_CONFLICT);
        }

        // 如果没有提交记录就不会进入保护状态，不需要拉取
        log.info("用户 {} 允许更新分支 {}", owner, branch);

        // 分支保护：受保护分支且已有历史时，禁止直接 push（须走 PR 流程）。
        // 例外：仓库 OWNER 可绕过（对应设计中的 bypass 白名单）。
        if (!collaborator.getLevel().equals(CollaboratorLevelEnum.OWNER)
                && BranchVerification.isProtectedBranch(branchPointer)
                && branchPointer.getCommitSha() != null) {
            log.warn("分支 {} 受保护，且用户 {} 非 OWNER，拒绝直接推送", branch, owner);
            // 发布推送事件(分支被保护)
            applicationEventPublisher.publishEvent(new PushEvent(this, repositoryId, branch, sha1, userId, true));
            return Result.fail(ResultCode.BRANCH_PROTECTED, MsgConstant.THE_BRANCH_IS_PROTECTED);
        }

        // 用户权限通过，分支不受保护，更新分支指向提交
        if (!pushWrite(branchPointer, sha1, repositoryId, collaborator, branch)) {
            // 更新失败，返回版本冲突
            return Result.fail(ResultCode.VERSION_CONFLICT, MsgConstant.VERSION_CONFLICT);
        }
        // 发布推送事件(分支更新)
        applicationEventPublisher.publishEvent(new PushEvent(this, repositoryId, branch, sha1, userId, false));

        return Result.ok();
    }

    /**
     * push 到远程仓库
     * 第二步：强制更新分支
     *
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (required) 分支名（如 master）
     * @param refUpdateRequest  (required) 提交之后远程 commit 的SHA1值
     * @return 强制更新分支的结果
     */
    @Override
    @Transactional
    public Result<RefView> pushForceUpdateBranch(String owner, String repo,
                                                 String branch, RefUpdateRequest refUpdateRequest) {
        // 获取用户id
        Long userId = remoteUserMapper.getUserId(owner);
        // 获取仓库id
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(userId, repo);
        // 获取分支指针
        BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, branch);
        // 获取提交者信息
        Collaborator collaborator = remoteCollaborator.getCollaborator(userId, repositoryId);

        // 强制推送只针对已存在的分支；分支不存在直接报错（新分支请用普通 push 创建）
        if (branchPointer == null) {
            log.warn("强制推送失败：分支 {} 不存在", branch);
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }

        // 检查用户权限
        if (!PermissionValidation.identityVerification(collaborator)) {
            log.warn("用户 {} 没有权限更新分支 {}", owner, branch);
//            log.info("协作者信息：{}", collaborator.toString());
            return Result.fail(ResultCode.UNAUTHORIZED, MsgConstant.UNAUTHORIZED);
        }
        // 检查是否允许强制推送
        if (!BranchVerification.isForcePushAllowed(branchPointer)) {
            // 不允许强制推送
            return Result.fail(ResultCode.FORBIDDEN, MsgConstant.FORBIDDEN_FORCE_PUSH);
        }

        log.info("强制更新分支 {} {}", branch, refUpdateRequest);
        pushWrite(branchPointer, refUpdateRequest.getSha(), repositoryId, collaborator, branch);

        return Result.ok();
    }



    /**
     * 创建远程仓库
     * @param repositoryCreateDTO  (required) 创建仓库的DTO
     * @return 创建仓库的结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ObjectUploadResult> createRemoteRepository(RepositoryCreateDTO repositoryCreateDTO) {
        String owner = repositoryCreateDTO.getOwner();
        String repo = repositoryCreateDTO.getRepo();

        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        // 校验仓库是否存在
        if (RepositoryVerification.repositoryExists(repositoryPaths)) {
            // 仓库存在则禁止重复创建
            return Result.fail(ResultCode.REPOSITORY_ALREADY_EXISTS, MsgConstant.THE_WAREHOUSE_ALREADY_EXISTS);
        }

        // 获取当前用户ID(根据用户名字查)
        Long ownerId = remoteUserMapper.getUserId(owner);
        if (ownerId == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND, MsgConstant.THE_USER_DOES_NOT_EXIST);
        }
        log.info("当前仓库 {} 的所有者ID为 {}", repo, ownerId);
        // 创建仓库对象
        Repository repository = Repository.builder()
                .ownerId(ownerId)
                .name(repo)
                .description(repositoryCreateDTO.getDescription())
                .visibility(0)
                .defaultBranch(MsgConstant.MAIN_BRANCH)
                .allowAutoPr(1)                    // 默认允许自动 PR
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();

        // 存入到数据库
        remoteRepositoryMapper.insert(repository);

        // 创建默认分支指针对象
        BranchPointer branchPointer = BranchPointer.builder()
                .repositoryId(repository.getId())        // 仓库ID
                .branchName(MsgConstant.MAIN_BRANCH)     // 默认分支名为 master
                .commitSha(null)                         // 随机生成的SHA1值，作为初始提交的SHA1值，防止报错
                .isDefault(1)                            // 1 表示默认分支，0 表示非默认分支
                .isProtected(1)                          // 1 表示受保护的分支，0 表示非受保护的分支
                .version(0)                              // 版本号初始为 0
                .updateTime(LocalDateTime.now())         // 更新时间初始为当前时间
                .isDeleted(0)                            // 0 表示未删除
                .isForcePush(0)                          // 1 表示允许强制推送，0 表示不允许强制推送
                .build();

        // 插入分支指针
        remoteBranchPointer.insert(branchPointer);

        // 更新用户权限
        Collaborator collaborator = Collaborator.builder()
                .repositoryId(repository.getId())
                .userId(ownerId)
                .level(CollaboratorLevelEnum.OWNER)
                .autoCreatePr(1)                   // 默认开启自动 PR
                .createTime(LocalDateTime.now())
                .isDeleted(0)
                .build();

        remoteCollaborator.insert(collaborator);

        // 如果仓库不存在则创建仓库（前边报错则不去创建）
        RepositoryVerification.createRepository(repositoryPaths);

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
//        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
//        // 当前分支文件
//        File branchFile = Utils.join(repositoryPaths.getBranchDir(), branch);

        Long ownerId = remoteUserMapper.getUserId(owner);
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(ownerId, repo);
        BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, branch);

        // 判断当前分支是否存在，如果不存在则返回错误码30004
        if (branchPointer == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }

        log.info("分支 {} 的指向为 {}", branch, branchPointer.getCommitSha());

        // 如果存在则返回 分支所指向的提交
        return Result.ok(new RefView().branch(branch).sha(branchPointer.getCommitSha()));
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
        // 提交不能为空（可能有commit但是没有blob，但是不可能有blob但是没有commit，Blob是只增不删）
        if (CollUtil.isEmpty(commitNames) && CollUtil.isEmpty(blobNames)) {
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

    /**
     * 推送写入
     * @param branchPointer 分支指针
     * @param sha1 提交SHA1值
     * @param repositoryId 仓库ID
     * @param collaborator 提交者
     * @param branch 分支名
     */
    private boolean pushWrite(BranchPointer branchPointer, String sha1, Long repositoryId,
                           Collaborator collaborator, String branch) {
        // 用户权限通过，分支不受保护，更新分支指向提交
        int link = remoteBranchPointer.updateBranchPointer(branchPointer.getId(), sha1, branchPointer.getVersion());
        if (link == 0) {
            log.warn("分支 {} 乐观锁冲突，期望 version={}", branch, branchPointer.getVersion());
            return false;
        }
        // 创建提交记录
        SubmissionRecord submissionRecord = SubmissionRecord.builder()
                .repositoryId(repositoryId)              // 仓库ID
                .commitSha(sha1)                         // 提交SHA1值
                .userId(collaborator.getUserId())        // 提交者ID
                .branchName(branch)                      // 分支名
                .submitTime(LocalDateTime.now())         // 提交时间
                .build();

        // 插入提交记录
        remoteSubmissionRecordMapper.insert(submissionRecord);

        // 创建提交图谱索引
        CommitParent commitParent = CommitParent.builder()
                .repositoryId(repositoryId)              // 仓库ID
                .commitSha(sha1)                         // 提交SHA1值
                .parentSha(branchPointer.getCommitSha()) // 父提交SHA1值
                .build();

        // 插入提交图谱索引
        remoteCommitPointer.insert(commitParent);
        return true;
    }

    /* ==================== 读取接口 ==================== */

    /** 历史遍历的最大节点数，防止异常数据导致无界遍历 */
    private static final int MAX_HISTORY = 1000;

    /** 提交列表的默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public Result<List<BranchPointer>> listBranches(String owner, String repo) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        List<BranchPointer> branches = remoteBranchPointer.selectList(
                Wrappers.<BranchPointer>lambdaQuery()
                        .eq(BranchPointer::getRepositoryId, repositoryId)
                        .orderByDesc(BranchPointer::getIsDefault)
                        .orderByAsc(BranchPointer::getBranchName));
        return Result.ok(branches);
    }

    @Override
    public Result<List<CommitVO>> listCommits(String owner, String repo,
                                              String branch, Integer page, Integer size) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        if (branch == null || branch.isBlank()) {
            Repository repository = remoteRepositoryMapper.selectById(repositoryId);
            branch = (repository != null && repository.getDefaultBranch() != null)
                    ? repository.getDefaultBranch() : MsgConstant.MAIN_BRANCH;
        }
        BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, branch);
        if (branchPointer == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }
        if (branchPointer.getCommitSha() == null) {
            // 空仓库（建库时分支无指向）
            return Result.ok(new ArrayList<>());
        }

        List<CommitVO> all = walkHistory(resolvePaths(owner, repo), branchPointer.getCommitSha());
        enrichAuthors(all, repositoryId);

        // 内存分页：项目规模够用，且历史遍历本就一次性走完
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : size;
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        return Result.ok(new ArrayList<>(all.subList(from, to)));
    }

    @Override
    public Result<CommitVO> getCommit(String owner, String repo, String sha) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Commit commit = readCommitObject(resolvePaths(owner, repo), sha);
        if (commit == null) {
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }
        CommitVO vo = toCommitVO(sha, commit);
        vo.setTrackedFiles(commit.getTrackedFiles());
        enrichAuthors(List.of(vo), repositoryId);
        return Result.ok(vo);
    }

    @Override
    public Result<List<FileChangeVO>> diffCommit(String owner, String repo, String sha) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        RepositoryPaths paths = resolvePaths(owner, repo);
        Commit commit = readCommitObject(paths, sha);
        if (commit == null) {
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }

        Map<String, String> newer = commit.getTrackedFiles() == null
                ? Collections.emptyMap() : commit.getTrackedFiles();
        // 与第一父提交比较；根提交视为全部新增
        Map<String, String> older = Collections.emptyMap();
        if (commit.getParent() != null) {
            Commit parent = readCommitObject(paths, commit.getParent());
            if (parent != null && parent.getTrackedFiles() != null) {
                older = parent.getTrackedFiles();
            }
        }

        List<FileChangeVO> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : newer.entrySet()) {
            String path = entry.getKey();
            String newSha = entry.getValue();
            String oldSha = older.get(path);
            if (Objects.equals(newSha, oldSha)) {
                continue;
            }
            FileChangeVO change = new FileChangeVO();
            change.setPath(path);
            change.setNewSha(newSha);
            change.setOldSha(oldSha);
            boolean added = oldSha == null;
            change.setChangeType(added ? "ADDED" : "MODIFIED");
            change.setNewContent(readTextBlob(paths, newSha));
            if (!added) {
                change.setOldContent(readTextBlob(paths, oldSha));
            }
            changes.add(change);
        }
        for (Map.Entry<String, String> entry : older.entrySet()) {
            if (newer.containsKey(entry.getKey())) {
                continue;
            }
            FileChangeVO change = new FileChangeVO();
            change.setPath(entry.getKey());
            change.setChangeType("DELETED");
            change.setOldSha(entry.getValue());
            change.setOldContent(readTextBlob(paths, entry.getValue()));
            changes.add(change);
        }
        changes.sort(Comparator.comparing(FileChangeVO::getPath));
        return Result.ok(changes);
    }

    @Override
    public Result<List<FileEntryVO>> listTree(String owner, String repo, String ref) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Commit commit = resolveCommit(resolvePaths(owner, repo), ref, repositoryId);
        if (commit == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }
        List<FileEntryVO> entries = new ArrayList<>();
        if (commit.getTrackedFiles() != null) {
            for (Map.Entry<String, String> entry : commit.getTrackedFiles().entrySet()) {
                entries.add(new FileEntryVO(entry.getKey(), entry.getValue()));
            }
            entries.sort(Comparator.comparing(FileEntryVO::getPath));
        }
        return Result.ok(entries);
    }

    @Override
    public Result<BlobVO> getBlob(String owner, String repo, String ref, String path) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        RepositoryPaths paths = resolvePaths(owner, repo);
        Commit commit = resolveCommit(paths, ref, repositoryId);
        if (commit == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }
        Map<String, String> tracked = commit.getTrackedFiles();
        String blobSha = tracked == null ? null : tracked.get(path);
        if (blobSha == null) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_FILE_DOES_NOT_EXIST);
        }

        byte[] bytes = readBlobBytes(paths, blobSha);
        if (bytes == null) {
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }
        BlobVO vo = new BlobVO();
        vo.setPath(path);
        vo.setBlobSha(blobSha);
        if (isBinary(bytes)) {
            vo.setIsBinary(true);
            vo.setContent("");
        } else {
            vo.setIsBinary(false);
            vo.setContent(new String(bytes, StandardCharsets.UTF_8));
        }
        return Result.ok(vo);
    }

    /* ==================== 读取接口的私有辅助 ==================== */

    /** owner/repo -> repositoryId；任一环节缺失返回 null */
    private Long resolveRepositoryId(String owner, String repo) {
        Long ownerId = remoteUserMapper.getUserId(owner);
        if (ownerId == null) {
            return null;
        }
        return remoteRepositoryMapper.getRepositoryId(ownerId, repo);
    }

    private RepositoryPaths resolvePaths(String owner, String repo) {
        return new RepositoryPaths(owner + "/" + repo, new File(basePath));
    }

    /** 读取磁盘上的提交对象；文件缺失或反序列化失败返回 null */
    private Commit readCommitObject(RepositoryPaths paths, String sha) {
        if (sha == null) {
            return null;
        }
        File commitFile = Utils.join(paths.getCommits(), sha);
        if (!commitFile.isFile()) {
            return null;
        }
        try {
            return Utils.readObject(commitFile, Commit.class);
        } catch (IllegalArgumentException e) {
            log.warn("读取提交对象 {} 失败: {}", sha, e.getMessage());
            return null;
        }
    }

    /**
     * 按父链倒序遍历提交（提交图以 commit 对象内的 parent/secondParent 为准，
     * 不依赖 commit_parent 表——该表每次推送只写一条边，批量推送的中间提交没有记录）。
     */
    private List<CommitVO> walkHistory(RepositoryPaths paths, String startSha) {
        List<CommitVO> result = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(startSha);
        while (!stack.isEmpty() && result.size() < MAX_HISTORY) {
            String sha = stack.pop();
            if (!visited.add(sha)) {
                continue;
            }
            Commit commit = readCommitObject(paths, sha);
            if (commit == null) {
                continue;
            }
            result.add(toCommitVO(sha, commit));
            // 栈后进先出：先压第二父，再压第一父，保证主链先被遍历
            if (commit.getSecondParent() != null) {
                stack.push(commit.getSecondParent());
            }
            if (commit.getParent() != null) {
                stack.push(commit.getParent());
            }
        }
        return result;
    }

    /** ref 既可能是提交 SHA，也可能是分支名 */
    private Commit resolveCommit(RepositoryPaths paths, String ref, Long repositoryId) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, ref);
        String sha = (branchPointer != null && branchPointer.getCommitSha() != null)
                ? branchPointer.getCommitSha() : ref;
        return readCommitObject(paths, sha);
    }

    /** 补齐作者信息：submission_record 通常只有分支 tip 有记录，其余为 null，前端按 null 降级 */
    private void enrichAuthors(List<CommitVO> commits, Long repositoryId) {
        if (CollUtil.isEmpty(commits)) {
            return;
        }
        List<String> shas = commits.stream().map(CommitVO::getSha).collect(Collectors.toList());
        List<SubmissionRecord> records = remoteSubmissionRecordMapper.selectList(
                Wrappers.<SubmissionRecord>lambdaQuery()
                        .eq(SubmissionRecord::getRepositoryId, repositoryId)
                        .in(SubmissionRecord::getCommitSha, shas));

        Map<String, SubmissionRecord> bySha = new HashMap<>();
        for (SubmissionRecord record : records) {
            bySha.merge(record.getCommitSha(), record, (a, b) -> {
                if (a.getSubmitTime() == null) {
                    return b;
                }
                if (b.getSubmitTime() == null) {
                    return a;
                }
                return b.getSubmitTime().isAfter(a.getSubmitTime()) ? b : a;
            });
        }

        Map<Long, String> usernameCache = new HashMap<>();
        for (CommitVO vo : commits) {
            SubmissionRecord record = bySha.get(vo.getSha());
            if (record == null) {
                continue;
            }
            vo.setAuthorId(record.getUserId());
            vo.setBranchName(record.getBranchName());
            if (record.getUserId() != null) {
                String username = usernameCache.get(record.getUserId());
                if (username == null) {
                    User user = remoteUserMapper.selectById(record.getUserId());
                    username = user == null ? null : user.getUsername();
                    usernameCache.put(record.getUserId(), username);
                }
                vo.setAuthorName(username);
            }
        }
    }

    private CommitVO toCommitVO(String sha, Commit commit) {

        CommitVO vo = new CommitVO();
        vo.setSha(sha);
        vo.setMessage(commit.getMessage());
        vo.setTime(commit.getTime());
        vo.setParent(commit.getParent());
        vo.setSecondParent(commit.getSecondParent());
        vo.setMergeFiles(commit.getMergeFiles());
        return vo;
    }

    private byte[] readBlobBytes(RepositoryPaths paths, String blobSha) {
        if (blobSha == null) {
            return null;
        }
        File blobFile = Utils.join(paths.getObject(), blobSha);
        if (!blobFile.isFile()) {
            return null;
        }
        try {
            return Utils.readContents(blobFile);
        } catch (IllegalArgumentException e) {
            log.warn("读取 Blob {} 失败: {}", blobSha, e.getMessage());
            return null;
        }
    }

    /** diff 里只回文本；二进制返回 null（不把二进制塞进 JSON） */
    private String readTextBlob(RepositoryPaths paths, String blobSha) {
        byte[] bytes = readBlobBytes(paths, blobSha);
        if (bytes == null || isBinary(bytes)) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 简易二进制探测：出现 NUL 字节即视为二进制 */
    private boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

}

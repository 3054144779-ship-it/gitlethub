package org.openapitools.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.sql.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.openapitools.service.support.RepositoryObjectReader;
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

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;


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

    @Autowired
    private RepositoryObjectReader repositoryObjectReader;

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
        if (!pushWrite(branchPointer, sha1, repositoryId, collaborator, branch, repositoryPaths)) {
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
        RepositoryPaths repositoryPaths = new RepositoryPaths(owner + "/" + repo, new File(basePath));
        pushWrite(branchPointer, refUpdateRequest.getSha(), repositoryId, collaborator, branch, repositoryPaths);

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
     * 拉取远程仓库（增量；since 为空时为全量，等价 clone）
     * 增量更新
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param since  (optional) 客户端已有的最新提交 sha；为空表示全量拉取
     * @return 拉取的提交列表
     */
    @Override
    public Result<ObjectBundle> pull(String owner, String repo, String since) {
        log.info("拉取 {} / {} 从 {} 开始的提交", owner, repo, since);

        // 获取仓库路径
        RepositoryPaths repositoryPaths = repositoryObjectReader.resolvePaths(owner, repo);
        // 检查仓库是否存在(不存在则报错)
        if (!RepositoryVerification.repositoryExists(repositoryPaths)) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }

        Long repositoryId = repositoryObjectReader.resolveRepositoryId(owner, repo);

        // 仓库ID不能为空
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }

        // 读取增量/全量提交与 Blob，用 Set 天然去重
        Set<String> commitNames = new LinkedHashSet<>();
        // 跨提交引用同一 blob 时去重，避免重复传输
        Set<String> blobNames = new LinkedHashSet<>();

        // since 为空 = 首次全量拉取（等价 clone 语义）：直接收下对象库全部提交与 Blob
        if (StrUtil.isEmpty(since)) {
            List<String> allCommits = Utils.plainFilenamesIn(repositoryPaths.getCommits());
            List<String> allBlobs = Utils.plainFilenamesIn(repositoryPaths.getObject());
            if (allCommits != null) {
                commitNames.addAll(allCommits);
            }
            if (allBlobs != null) {
                blobNames.addAll(allBlobs);
            }
        } else {
            // 获取当前提交的提交记录：同一 sha 可能推到多个分支，用 selectList 取首条，
            // 避免 selectOne 命中多行直接抛 TooManyResultsException；同时用 repository_id 限定范围
            List<SubmissionRecord> records = remoteSubmissionRecordMapper.selectList(
                    new QueryWrapper<SubmissionRecord>()
                            .eq("repository_id", repositoryId)
                            .eq("commit_sha", since));
            SubmissionRecord submissionRecord = CollUtil.isEmpty(records) ? null : records.get(0);

            // 提交记录不能为空且仓库ID必须一致
            if (submissionRecord == null || !submissionRecord.getRepositoryId().equals(repositoryId)) {
                return Result.fail(ResultCode.PULL_FAILED, MsgConstant.REJECT_MERGING_IRRELEVANT_HISTORY);
            }

            // 获取提交分支指向的 commit Sha1
            String branchName = submissionRecord.getBranchName();
            BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, branchName);
            if (branchPointer == null) {
                return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
            }

            log.info("分支 {} 的指向为 {}", branchName, branchPointer.getCommitSha());

            // 从分支 tip 反向回溯到 since（不含）为止
            Deque<String> stack = new ArrayDeque<>();
            stack.push(branchPointer.getCommitSha());

            while (!stack.isEmpty()) {
                String nowCommitSha = stack.pop();
                // 走到 since 即截断（since 客户端已有，不下发）；null 保护防止走到根提交的空父导致 NPE
                if (nowCommitSha == null || nowCommitSha.equals(since)) {
                    continue;
                }
                // 合并提交两条父链交汇时防止重复处理
                if (commitNames.contains(nowCommitSha)) {
                    continue;
                }
                Commit commit = repositoryObjectReader.readCommitObject(repositoryPaths, nowCommitSha);
                if (commit == null) {
                    continue;
                }
                commitNames.add(nowCommitSha);
                if (commit.getTrackedFiles() != null) {
                    blobNames.addAll(commit.getTrackedFiles().values());
                }
                // 双父都入栈：合并提交的 secondParent 侧历史同样需要下发（原来只走 parent 会漏）
                if (commit.getSecondParent() != null) {
                    stack.push(commit.getSecondParent());
                }
                if (commit.getParent() != null) {
                    stack.push(commit.getParent());
                }
            }
        }

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
                .collect(java.util.stream.Collectors.toList());

        // Blob列表
        List<GitletObject> blobList = blobNames.stream()
                .map(blob -> new GitletObject()
                        .type(GitletObject.TypeEnum.BLOB)
                        .content(Utils.readContents(Utils.join(repositoryPaths.getObject(), blob)))
                        .sha1(blob))
                .collect(java.util.stream.Collectors.toList());

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
                           Collaborator collaborator, String branch, RepositoryPaths repositoryPaths) {
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

        Commit commit = Utils.readObject(Utils.join(repositoryPaths.getCommits(), sha1), Commit.class);

        // 创建提交图谱索引
        CommitParent commitParent = CommitParent.builder()
                .repositoryId(repositoryId)              // 仓库ID
                .commitSha(sha1)                         // 提交SHA1值
                .parentSha(commit.getParent()) // 父提交SHA1值
                .parentSecondSha(commit.getSecondParent())
                .build();

        // 插入提交图谱索引
        remoteCommitPointer.insert(commitParent);
        return true;
    }

}

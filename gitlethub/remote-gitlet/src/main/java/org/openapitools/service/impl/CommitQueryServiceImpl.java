package org.openapitools.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.Result;
import common.ResultCode;
import gitlet.commit.Commit;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteSubmissionRecordMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.service.CommitQueryService;
import org.openapitools.service.support.RepositoryObjectReader;
import org.openapitools.utils.RedisCacheUtils;
import org.openapitools.utils.UserNameCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import po.BranchPointer;
import po.Repository;
import po.SubmissionRecord;
import vo.CommitVO;
import vo.FileChangeVO;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 提交查询服务实现
 */
@Slf4j
@Service
public class CommitQueryServiceImpl implements CommitQueryService {

    /** 历史遍历的最大节点数，防止异常数据导致无界遍历 */
    private static final int MAX_HISTORY = 1000;

    /** 提交列表的默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 只缓存中等规模的历史，避免把上千条提交的 JSON 塞进 Redis */
    private static final int MAX_CACHED_COMMITS = 300;
    private static final String COMMITS_KEY_PREFIX = "repo:commits:";
    private static final Integer COMMITS_TTL_SECONDS = 300;

    @Autowired
    private RepositoryObjectReader reader;

    @Autowired
    private RedisCacheUtils redisCacheUtils;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserNameCache userNameCache;


    @Autowired
    private RemoteBranchPointerMapper remoteBranchPointer;

    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;

    @Autowired
    private RemoteSubmissionRecordMapper remoteSubmissionRecordMapper;

    /**
     * 提交列表
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @param branch  (optional) 分支名，缺省用仓库默认分支
     * @param page  (optional) 页码，从 1 开始，缺省 1
     * @param size  (optional) 每页条数，缺省 20
     * @return 提交列表
     */
    @Override
    public Result<List<CommitVO>> listCommits(String owner, String repo,
                                              String branch, Integer page, Integer size) {
        Long repositoryId = reader.resolveRepositoryId(owner, repo);
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

        List<CommitVO> all = loadHistory(owner, repo, repositoryId, branchPointer.getCommitSha());
        // 内存分页：项目规模够用，且历史遍历本就一次性走完
        return Result.ok(paginate(all, page, size));
    }

    /**
     * 历史遍历要读最多 1000 个磁盘对象，按分支 tip 缓存：
     * push / 合并都会推进 tip，key 自然换新，不需要显式失效。
     */
    private List<CommitVO> loadHistory(String owner, String repo, Long repositoryId, String tipSha) {
        String key = COMMITS_KEY_PREFIX + repositoryId + ":" + tipSha;
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<CommitVO>>() {
                });
            }
        } catch (Exception e) {
            log.warn("提交历史缓存读取失败，回源磁盘，key={}", key, e);
            evict(key);
        }

        List<CommitVO> all = walkHistory(reader.resolvePaths(owner, repo), tipSha);
        enrichAuthors(all, repositoryId);
        if (all.size() <= MAX_CACHED_COMMITS) {
            try {
                redisCacheUtils.set(key, objectMapper.writeValueAsString(all),
                        Duration.ofSeconds(COMMITS_TTL_SECONDS));
            } catch (Exception e) {
                log.warn("提交历史缓存写入失败，key={}", key, e);
            }
        }
        return all;
    }

    private List<CommitVO> paginate(List<CommitVO> all, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : size;
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    /** 缓存清理失败不能影响读请求 */
    private void evict(String key) {
        try {
            redisCacheUtils.delete(key);
        } catch (Exception e) {
            log.warn("缓存清理失败，key={}", key, e);
        }
    }

    @Override
    public Result<CommitVO> getCommit(String owner, String repo, String sha) {
        Long repositoryId = reader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        Commit commit = reader.readCommitObject(reader.resolvePaths(owner, repo), sha);
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
        Long repositoryId = reader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        RepositoryPaths paths = reader.resolvePaths(owner, repo);
        Commit commit = reader.readCommitObject(paths, sha);
        if (commit == null) {
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }

        Map<String, String> newer = commit.getTrackedFiles() == null
                ? Collections.emptyMap() : commit.getTrackedFiles();
        // 与第一父提交比较；根提交视为全部新增
        Map<String, String> older = Collections.emptyMap();
        if (commit.getParent() != null) {
            Commit parent = reader.readCommitObject(paths, commit.getParent());
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
            change.setNewContent(reader.readTextBlob(paths, newSha));
            if (!added) {
                change.setOldContent(reader.readTextBlob(paths, oldSha));
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
            change.setOldContent(reader.readTextBlob(paths, entry.getValue()));
            changes.add(change);
        }
        changes.sort(Comparator.comparing(FileChangeVO::getPath));
        return Result.ok(changes);
    }

    /**
     * 按父链倒序遍历提交（提交图以 commit 对象内的 parent/secondParent 为准，
     * 不依赖 commit_parent 表——该表每次推送只写一条边，批量推送的中间提交没有记录）。
     *
     * 使用DFS遍历提交历史，采用栈实现，避免递归调用导致栈溢出
     * @param paths 仓库路径
     * @param startSha 起始提交 SHA
     * @return 提交列表
     */
    private List<CommitVO> walkHistory(RepositoryPaths paths, String startSha) {
        List<CommitVO> result = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        // DFS遍历提交历史
        stack.push(startSha);
        while (!stack.isEmpty() && result.size() < MAX_HISTORY) {
            String sha = stack.pop();
            if (!visited.add(sha)) {
                continue;
            }
            Commit commit = reader.readCommitObject(paths, sha);
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

    /**
     * 补齐作者信息：submission_record 通常只有分支 tip 有记录，其余为 null，前端按 null 降级
     * @param commits 提交列表
     * @param repositoryId 仓库 ID
     */
    private void enrichAuthors(List<CommitVO> commits, Long repositoryId) {
        if (CollUtil.isEmpty(commits)) {
            return;
        }
        List<String> shas = commits.stream().map(CommitVO::getSha).collect(Collectors.toList());
        // 查询提交记录
        List<SubmissionRecord> records = remoteSubmissionRecordMapper.selectList(
                Wrappers.<SubmissionRecord>lambdaQuery()
                        .eq(SubmissionRecord::getRepositoryId, repositoryId)
                        .in(SubmissionRecord::getCommitSha, shas));
        // 对查询出来的 SubmissionRecord 记录进行“按 commit SHA 去重”，并在多条记录中，保留“提交时间（submitTime）最新”的那一条
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
                    username = userNameCache.usernameOf(record.getUserId());
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
}

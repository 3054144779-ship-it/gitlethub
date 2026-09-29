package org.openapitools.service;

import common.Result;
import vo.CommitVO;
import vo.FileChangeVO;

import java.util.List;

/** 提交历史的只读查询（前端提交列表 / 提交详情 / diff） */
public interface CommitQueryService {

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
}

package org.openapitools.service;

import common.Result;
import po.BranchPointer;

import java.util.List;

/** 分支的只读查询（前端分支列表） */
public interface BranchQueryService {

    /**
     * 列出仓库的所有分支
     * @param owner  (required) 当前仓库的所有者
     * @param repo  (required) 当前仓库的名称
     * @return 分支列表
     */
    Result<List<BranchPointer>> listBranches(String owner, String repo);
}

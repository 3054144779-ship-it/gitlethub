package org.openapitools.service;

import common.Result;
import vo.BlobVO;
import vo.FileEntryVO;

import java.util.List;

/** 文件树与文件内容的只读查询（前端文件浏览） */
public interface BlobQueryService {

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

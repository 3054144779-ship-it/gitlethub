package org.openapitools.service.impl;

import common.Result;
import common.ResultCode;
import gitlet.commit.Commit;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.service.BlobQueryService;
import org.openapitools.service.support.RepositoryObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import po.BranchPointer;
import vo.BlobVO;
import vo.FileEntryVO;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Blob 查询服务实现
 */
@Service
public class BlobQueryServiceImpl implements BlobQueryService {

    @Autowired
    private RepositoryObjectReader reader;

    @Autowired
    private RemoteBranchPointerMapper remoteBranchPointer;

    @Override
    public Result<List<FileEntryVO>> listTree(String owner, String repo, String ref) {
        Long repositoryId = reader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        // 建库时初始化的分支存在但还没有提交：文件树为空，而不是“分支不存在”
        BranchPointer pointer = remoteBranchPointer.getBranchPointer(repositoryId, ref);
        if (pointer != null && (pointer.getCommitSha() == null || pointer.getCommitSha().isBlank())) {
            return Result.ok(new ArrayList<>());
        }
        Commit commit = resolveCommit(reader.resolvePaths(owner, repo), ref, repositoryId);
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
        Long repositoryId = reader.resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST);
        }
        RepositoryPaths paths = reader.resolvePaths(owner, repo);
        Commit commit = resolveCommit(paths, ref, repositoryId);
        if (commit == null) {
            return Result.fail(ResultCode.BRANCH_NOT_FOUND_REFS, MsgConstant.THE_BRANCH_DOES_NOT_EXIST);
        }
        Map<String, String> tracked = commit.getTrackedFiles();
        String blobSha = tracked == null ? null : tracked.get(path);
        if (blobSha == null) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.THE_FILE_DOES_NOT_EXIST);
        }

        byte[] bytes = reader.readBlobBytes(paths, blobSha);
        if (bytes == null) {
            return Result.fail(ResultCode.SHA1_MISMATCH, MsgConstant.THIS_SUBMISSION_DOES_NOT_EXIST);
        }
        BlobVO vo = new BlobVO();
        vo.setPath(path);
        vo.setBlobSha(blobSha);
        if (reader.isBinary(bytes)) {
            vo.setIsBinary(true);
            vo.setContent("");
        } else {
            vo.setIsBinary(false);
            vo.setContent(new String(bytes, StandardCharsets.UTF_8));
        }
        return Result.ok(vo);
    }

    /**
     * 解析提交对象
     * @param paths 仓库路径
     * @param ref 既可能是提交 SHA，也可能是分支名
     * @param repositoryId 仓库 ID
     * @return 提交对象
     */
    private Commit resolveCommit(RepositoryPaths paths, String ref, Long repositoryId) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        BranchPointer branchPointer = remoteBranchPointer.getBranchPointer(repositoryId, ref);
        String sha = (branchPointer != null && branchPointer.getCommitSha() != null)
                ? branchPointer.getCommitSha() : ref;
        return reader.readCommitObject(paths, sha);
    }
}

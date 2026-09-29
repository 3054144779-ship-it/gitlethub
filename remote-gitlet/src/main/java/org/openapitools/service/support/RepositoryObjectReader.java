package org.openapitools.service.support;

import gitlet.commit.Commit;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 只读查询服务共享的底层访问器：解析 basePath、把 owner/repo 解析成 repositoryId，
 * 以及从磁盘对象库读取 commit / blob。所有方法都是只读的，不修改任何状态。
 */
@Slf4j
@Component
public class RepositoryObjectReader {

    @Value("${storage.base-path:${user.dir}}")
    private String basePath;

    @Autowired
    private RemoteUserMapper remoteUserMapper;

    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;

    /** owner/repo -> repositoryId；任一环节缺失返回 null */
    public Long resolveRepositoryId(String owner, String repo) {
        Long ownerId = remoteUserMapper.getUserId(owner);
        if (ownerId == null) {
            return null;
        }
        return remoteRepositoryMapper.getRepositoryId(ownerId, repo);
    }

    public RepositoryPaths resolvePaths(String owner, String repo) {
        return new RepositoryPaths(owner + "/" + repo, new File(basePath));
    }

    /** 读取磁盘上的提交对象；文件缺失或反序列化失败返回 null */
    public Commit readCommitObject(RepositoryPaths paths, String sha) {
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

    /** 读取 blob 的原始字节；文件缺失或读取失败返回 null */
    public byte[] readBlobBytes(RepositoryPaths paths, String blobSha) {
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
    public String readTextBlob(RepositoryPaths paths, String blobSha) {
        byte[] bytes = readBlobBytes(paths, blobSha);
        if (bytes == null || isBinary(bytes)) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 简易二进制探测：出现 NUL 字节(0x00)即视为二进制
     * 局限：
     * 1.误判文本为二进制：如果文本文件是用 UTF-16 编码的（Windows 记事本有时候会这样存），里面也会包含 0x00 字节。
     * 这时候这段代码就会误以为它是二进制文件。
     * 2.误判二进制为文本：如果一个二进制文件恰好前几个字节没有 0x00，就会被当成文本文件强行读取。
     * @param bytes 字节数组
     * @return 是否为二进制文件
     */
    public boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}

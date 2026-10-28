package org.openapitools.service.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import gitlet.commit.Commit;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RepositoryPaths;
import org.openapitools.utils.RedisCacheUtils;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import po.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

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

    @Autowired
    private RedisCacheUtils redisCacheUtils;

    @Autowired
    private ObjectMapper objectMapper;

    /** 用户名、仓库名都没有改名入口，长 TTL 解析缓存不会显示旧数据 */
    private static final String REPO_ID_KEY_PREFIX = "repository:id:";
    private static final String REPO_ROW_KEY_PREFIX = "repository:row:";
    private static final Integer LOOKUP_TTL_SECONDS = 600;
    private static final Integer ROW_TTL_BASE_SECONDS = 60;
    private static final Integer ROW_TTL_JITTER_SECONDS = 30;

    /** owner/repo -> repositoryId；任一环节缺失返回 null */
    public Long resolveRepositoryId(String owner, String repo) {
        if (StrUtil.isBlank(owner) || StrUtil.isBlank(repo)) {
            return null;
        }
        // 直接按 owner 名缓存：命中时省掉「用户名查 id」+「owner/repo 查 id」两次 SQL
        String key = REPO_ID_KEY_PREFIX + owner + ":" + repo;
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return Long.valueOf(cached);
            }
        } catch (Exception e) {
            log.warn("仓库ID缓存读取失败，回源数据库，key={}", key, e);
        }
        Long ownerId = remoteUserMapper.getUserId(owner);
        if (ownerId == null) {
            return null;
        }
        Long repositoryId = remoteRepositoryMapper.getRepositoryId(ownerId, repo);
        // 只缓存命中结果：负缓存会让刚建好的仓库在 TTL 内一直解析不到
        if (repositoryId != null) {
            setQuietly(key, String.valueOf(repositoryId), Duration.ofSeconds(LOOKUP_TTL_SECONDS));
        }
        return repositoryId;
    }

    /**
     * owner/repo -> 仓库行。可见性拦截器每次读请求都要判断公开/私有与 ownerId，
     * 复用这份缓存可以省掉每请求 3 次用户表/仓库表查询。
     */
    public Repository resolveRepository(String owner, String repo) {
        Long repositoryId = resolveRepositoryId(owner, repo);
        if (repositoryId == null) {
            return null;
        }
        String key = REPO_ROW_KEY_PREFIX + repositoryId;
        try {
            String cached = redisCacheUtils.get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, Repository.class);
            }
        } catch (Exception e) {
            log.warn("仓库详情缓存读取失败，回源数据库，key={}", key, e);
            deleteQuietly(key);
        }
        Repository repository = remoteRepositoryMapper.selectById(repositoryId);
        if (repository != null) {
            try {
                setQuietly(key, objectMapper.writeValueAsString(repository), rowTtl());
            } catch (Exception e) {
                log.warn("仓库详情缓存写入失败，key={}", key, e);
            }
        }
        return repository;
    }

    /** 仓库配置变更（可见性/默认分支等）后必须失效，否则拦截器会按旧可见性放行 */
    public void evictRepository(Long repositoryId) {
        if (repositoryId == null) {
            return;
        }
        deleteQuietly(REPO_ROW_KEY_PREFIX + repositoryId);
    }

    private Duration rowTtl() {
        return Duration.ofSeconds(ROW_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(ROW_TTL_JITTER_SECONDS));
    }

    private void setQuietly(String key, String value, Duration ttl) {
        try {
            redisCacheUtils.set(key, value, ttl);
        } catch (Exception e) {
            log.warn("缓存写入失败，key={}", key, e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            redisCacheUtils.delete(key);
        } catch (Exception e) {
            log.warn("缓存清理失败，key={}", key, e);
        }
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

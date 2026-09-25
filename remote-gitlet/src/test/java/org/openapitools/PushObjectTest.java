package org.openapitools;

import common.Result;
import common.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.model.GitletObject;
import org.openapitools.model.ObjectUploadRequest;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.openapitools.utils.Utils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pushObject 单元测试：只涉及文件系统读写，不依赖数据库，
 * 因此直接 new 出 Service 并注入 basePath，用 @TempDir 提供隔离的临时磁盘目录。
 */
class PushObjectTest {

    private RemoteServiceImpl remoteService;

    @TempDir
    Path tempDir;

    private static final String OWNER = "alice";
    private static final String REPO = "test-repo";
    private static final String BLOB_SHA = "a".repeat(40);
    private static final String COMMIT_SHA = "b".repeat(40);

    @BeforeEach
    void setUp() {
        remoteService = new RemoteServiceImpl();
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
    }

    private File repoRoot() {
        return tempDir.resolve(".gitlet/" + OWNER + "/" + REPO).toFile();
    }

    private File objectsDir() {
        return tempDir.resolve(".gitlet/" + OWNER + "/" + REPO + "/Objects").toFile();
    }

    private File commitsDir() {
        return tempDir.resolve(".gitlet/" + OWNER + "/" + REPO + "/Objects/commits").toFile();
    }

    /** 模拟一个已存在的远程仓库目录结构 */
    private void createRepoDirs() {
        repoRoot().mkdirs();
        objectsDir().mkdirs();
        commitsDir().mkdirs();
    }

    /**
     * 测试正常推送对象，验证写入磁盘
     */
    @Test
    void pushObject_success_writesBlobAndCommitToDisk() {
        createRepoDirs();
        byte[] blobContent = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] commitContent = "commit-data".getBytes(StandardCharsets.UTF_8);

        GitletObject blob = new GitletObject()
                .sha1(BLOB_SHA).type(GitletObject.TypeEnum.BLOB).content(blobContent);
        GitletObject commit = new GitletObject()
                .sha1(COMMIT_SHA).type(GitletObject.TypeEnum.COMMIT).content(commitContent);

        Result<ObjectUploadResult> result =
                remoteService.pushObject(OWNER, REPO, new ObjectUploadRequest(List.of(blob, commit)));

        assertEquals(ResultCode.OK, result.getCode());
        File blobFile = new File(objectsDir(), BLOB_SHA);
        File commitFile = new File(commitsDir(), COMMIT_SHA);
        assertTrue(blobFile.exists(), "blob 应落到 Objects/ 目录");
        assertTrue(commitFile.exists(), "commit 应落到 Objects/commits/ 目录");
        assertArrayEquals(blobContent, Utils.readContents(blobFile));
        assertArrayEquals(commitContent, Utils.readContents(commitFile));
    }

    @Test
    void pushObject_existingObject_skippedNotOverwritten() {
        createRepoDirs();
        File blobFile = new File(objectsDir(), BLOB_SHA);
        byte[] old = "old-content".getBytes(StandardCharsets.UTF_8);
        Utils.writeContents(blobFile, (Object) old);

        GitletObject blob = new GitletObject()
                .sha1(BLOB_SHA).type(GitletObject.TypeEnum.BLOB)
                .content("new-content".getBytes(StandardCharsets.UTF_8));

        Result<ObjectUploadResult> result =
                remoteService.pushObject(OWNER, REPO, new ObjectUploadRequest(List.of(blob)));

        assertEquals(ResultCode.OK, result.getCode());
        assertArrayEquals(old, Utils.readContents(blobFile), "已存在的对象应被跳过，内容不被覆盖");
    }

    @Test
    void pushObject_repositoryNotExist_returnsRepositoryNotFound() {
        GitletObject blob = new GitletObject()
                .sha1(BLOB_SHA).type(GitletObject.TypeEnum.BLOB)
                .content("x".getBytes(StandardCharsets.UTF_8));

        Result<ObjectUploadResult> result =
                remoteService.pushObject(OWNER, REPO, new ObjectUploadRequest(List.of(blob)));

        assertEquals(ResultCode.REPOSITORY_NOT_FOUND, result.getCode());
    }

    @Test
    void pushObject_nullRequest_returnsFail() {
        createRepoDirs();
        Result<ObjectUploadResult> result = remoteService.pushObject(OWNER, REPO, null);
        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
    }
}

package org.openapitools;

import common.Result;
import common.ResultCode;
import dto.RepositoryCreateDTO;
import enums.CollaboratorLevelEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.mapper.*;
import org.openapitools.model.*;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import po.BranchPointer;
import po.Collaborator;
import po.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 远程核心流程集成测试：建仓库 → push 对象 → 更新分支 → pull → clone。
 * 真实文件系统（@TempDir）贯穿全流程，数据库层用 @MockBean 隔离。
 */
@SpringBootTest
class RemoteFlowIntegrationTest {

    @MockBean
    private RemoteUserMapper remoteUserMapper;
    @MockBean
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @MockBean
    private RemoteBranchPointerMapper remoteBranchPointer;
    @MockBean
    private RemoteCollaboratorMapper remoteCollaborator;
    @MockBean
    private RemoteSubmissionRecordMapper remoteSubmissionRecordMapper;
    @MockBean
    private RemoteCommitPointerMapper remoteCommitPointer;

    @Autowired
    private RemoteServiceImpl remoteService;

    @TempDir
    Path tempDir;

    private static final String OWNER = "alice";
    private static final String REPO = "test-repo";
    private static final String BLOB_SHA = "a".repeat(40);
    private static final String COMMIT_SHA = "b".repeat(40);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
    }

    /**
     * 完整的远程仓库核心流程：创建仓库 → push 对象 → 更新分支 → pull → clone。
     */
    @Test
    void fullPushPullFlow() {
        // ===== 1. 创建仓库 =====
        when(remoteUserMapper.getUserId(OWNER)).thenReturn(1L);
        when(remoteRepositoryMapper.insert(any(Repository.class))).thenAnswer(inv -> {
            inv.getArgument(0, Repository.class).setId(1L);
            return 1;
        });

        RepositoryCreateDTO dto = new RepositoryCreateDTO();
        dto.setOwner(OWNER);
        dto.setRepo(REPO);
        dto.setDescription("integration test repo");

        Result<ObjectUploadResult> createResult = remoteService.createRemoteRepository(dto);
        assertEquals(ResultCode.OK, createResult.getCode());
        assertTrue(tempDir.resolve(".gitlet/" + OWNER + "/" + REPO).toFile().exists());
        verify(remoteBranchPointer, times(1)).insert(any(BranchPointer.class));
        verify(remoteCollaborator, times(1)).insert(any(Collaborator.class));

        // ===== 2. push 上传对象（文件 IO）=====
        bo.GitletObject blob = new bo.GitletObject().sha1(BLOB_SHA)
                .type(bo.GitletObject.TypeEnum.BLOB)
                .content("hello".getBytes(StandardCharsets.UTF_8));
        bo.GitletObject commit = new bo.GitletObject().sha1(COMMIT_SHA)
                .type(bo.GitletObject.TypeEnum.COMMIT)
                .content("commit-data".getBytes(StandardCharsets.UTF_8));

        Result<ObjectUploadResult> pushObjResult =
                remoteService.pushObject(OWNER, REPO, new ObjectUploadRequest(List.of(blob, commit)));
        assertEquals(ResultCode.OK, pushObjResult.getCode());

        // ===== 3. push 更新分支（DB）=====
        // 首次推送：master 受保护但 commitSha 为 null，允许建立历史
        when(remoteRepositoryMapper.getRepositoryId(1L, REPO)).thenReturn(1L);
        when(remoteBranchPointer.getBranchPointer(OWNER, 1L, "master")).thenReturn(
                BranchPointer.builder().id(1L).repositoryId(1L).branchName("master")
                        .commitSha(null).isProtected(1).isForcePush(0).version(0).build());
        when(remoteCollaborator.getCollaborator(1L, 1L)).thenReturn(
                Collaborator.builder().id(1L).userId(1L).repositoryId(1L)
                        .level(CollaboratorLevelEnum.OWNER).build());
        // 乐观锁更新成功：返回受影响行数 1（@MockBean 默认返回 0 会被判成版本冲突）
        when(remoteBranchPointer.updateBranchPointer(1L, COMMIT_SHA, 0)).thenReturn(1);

        Result<RefView> refResult = remoteService.pushUpdateBranch(
                OWNER, REPO, "master", new RefUpdateRequest(COMMIT_SHA));
        assertEquals(ResultCode.OK, refResult.getCode());
        verify(remoteBranchPointer, times(1)).updateBranchPointer(1L, COMMIT_SHA, 0);

        // ===== 4. pull（文件 IO，全量）=====
        Result<bo.ObjectBundle> pullResult = remoteService.pull(OWNER, REPO, null);
        assertEquals(ResultCode.OK, pullResult.getCode());
        assertEquals(2, pullResult.getData().getObjects().size());

        // ===== 5. clone 复用 pull =====
        Result<bo.ObjectBundle> cloneResult = remoteService.cloneRepository(OWNER, REPO);
        assertEquals(ResultCode.OK, cloneResult.getCode());
        assertEquals(2, cloneResult.getData().getObjects().size());
    }
}

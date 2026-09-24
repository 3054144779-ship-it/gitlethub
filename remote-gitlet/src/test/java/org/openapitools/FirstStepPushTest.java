package org.openapitools;

import common.Result;
import common.ResultCode;
import enums.CollaboratorLevelEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import po.BranchPointer;
import po.Collaborator;

@SpringBootTest
public class FirstStepPushTest {

    @MockBean
    private RemoteUserMapper remoteUserMapper;

    @MockBean
    private RemoteRepositoryMapper remoteRepositoryMapper;

    @MockBean
    private RemoteBranchPointerMapper remoteBranchPointerMapper;

    @MockBean
    private RemoteCollaboratorMapper remoteCollaboratorMapper;

    @Autowired
    private RemoteServiceImpl remoteService;

    @TempDir
    Path tempDir;

    private static final String SHA1 = "a".repeat(40);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
    }

    /**
     * 创建分支指针
     * @param isProtected 是否受保护分支
     * @return 分支指针
     */
    private BranchPointer buildBranchPointer(boolean isProtected) {
        return BranchPointer.builder()
                .id(1L)
                .repositoryId(1L)
                .branchName("feature")
                .commitSha("b".repeat(40))
                .isProtected(isProtected ? 1 : 0) // 是否受保护分支
                .version(0)
                .isForcePush(0)
                .build();
    }

    /**
     * 创建协作者
     * @param level 协作者级别
     * @return 协作者
     */
    private Collaborator buildCollaborator(CollaboratorLevelEnum level) {
        return Collaborator.builder()
                .id(1L)
                .userId(1L)
                .repositoryId(1L)
                .level(level)
                .build();
    }

    /**
     * 模拟公共依赖
     * @param bp 分支指针
     * @param collab 协作者
     */
    private void mockCommonDependencies(BranchPointer bp, Collaborator collab) {
        when(remoteRepositoryMapper.getRepositoryId("alice", "test-repo")).thenReturn(1L);
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteBranchPointerMapper.getBranchPointer("alice", 1L, "feature")).thenReturn(bp);
        when(remoteCollaboratorMapper.getCollaborator(1L, 1L)).thenReturn(collab);
    }

    /**
     * 推送分支更新成功
     */
    @Test
    void pushUpdateBranch_success() {
        BranchPointer bp = buildBranchPointer(false);
        Collaborator collab = buildCollaborator(CollaboratorLevelEnum.OWNER);
        mockCommonDependencies(bp, collab);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req);
//        System.out.println(result);
        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteBranchPointerMapper, times(1)).updateBranchPointer(1L, SHA1);
    }

    /**
     * 推送分支更新，分支受保护，返回分支受保护
     */
    @Test
    void pushUpdateBranch_protectedBranch_returnsBranchProtected() {
        BranchPointer bp = buildBranchPointer(true);
        Collaborator collab = buildCollaborator(CollaboratorLevelEnum.OWNER);
        mockCommonDependencies(bp, collab);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req);

        assertEquals(ResultCode.BRANCH_PROTECTED, result.getCode());
        verify(remoteBranchPointerMapper, never()).updateBranchPointer(anyLong(), anyString());
    }

    /**
     * 推送分支更新，协作者级别为访客，返回未授权
     */
    @Test
    void pushUpdateBranch_unauthorizedGuest_returnsUnauthorized() {
        BranchPointer bp = buildBranchPointer(false);
        Collaborator collab = buildCollaborator(CollaboratorLevelEnum.GUEST);
        mockCommonDependencies(bp, collab);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req);

        assertEquals(ResultCode.UNAUTHORIZED, result.getCode());
        verify(remoteBranchPointerMapper, never()).updateBranchPointer(anyLong(), anyString());
    }

    /**
     * 推送分支更新，协作者不存在，返回未授权
     */
    @Test
    void pushUpdateBranch_nullCollaborator_returnsUnauthorized() {
        when(remoteRepositoryMapper.getRepositoryId("alice", "test-repo")).thenReturn(1L);
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        BranchPointer bp = buildBranchPointer(false);
        when(remoteBranchPointerMapper.getBranchPointer("alice", 1L, "feature")).thenReturn(bp);
        when(remoteCollaboratorMapper.getCollaborator(1L, 1L)).thenReturn(null);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req);

        assertEquals(ResultCode.UNAUTHORIZED, result.getCode());
        verify(remoteBranchPointerMapper, never()).updateBranchPointer(anyLong(), anyString());
    }
}
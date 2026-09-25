package org.openapitools;

import common.Result;
import common.ResultCode;
import enums.CollaboratorLevelEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.mapper.*;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;
import org.openapitools.service.impl.RemoteServiceImpl;
import po.BranchPointer;
import po.Collaborator;
import po.CommitParent;
import po.SubmissionRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * pushForceUpdateBranch 单元测试：只涉及数据库交互，用 Mockito 隔离全部 Mapper，
 * 不启动 Spring 容器、不连库。
 */
class PushForceUpdateBranchTest {

    @Mock
    private RemoteUserMapper remoteUserMapper;
    @Mock
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Mock
    private RemoteBranchPointerMapper remoteBranchPointer;
    @Mock
    private RemoteCollaboratorMapper remoteCollaborator;
    @Mock
    private RemoteSubmissionRecordMapper remoteSubmissionRecordMapper;
    @Mock
    private RemoteCommitPointerMapper remoteCommitPointer;

    @InjectMocks
    private RemoteServiceImpl remoteService;

    private static final String SHA1 = "c".repeat(40);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private BranchPointer buildBranchPointer(int isForcePush) {
        return BranchPointer.builder()
                .id(1L)
                .repositoryId(1L)
                .branchName("master")
                .commitSha("d".repeat(40))
                .isProtected(1)
                .isForcePush(isForcePush)
                .version(0)
                .build();
    }

    private void mockCommon(BranchPointer bp) {
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteBranchPointer.getBranchPointer("alice", 1L, "master")).thenReturn(bp);
        when(remoteCollaborator.getCollaborator(1L, 1L)).thenReturn(
                Collaborator.builder().id(1L).userId(1L).repositoryId(1L)
                        .level(CollaboratorLevelEnum.OWNER).build());
    }

    /**
     * 强制更新分支：允许的情况
     */
    @Test
    void forceUpdate_allowed_writesBranchAndRecords() {
        mockCommon(buildBranchPointer(1));
        // 乐观锁更新成功：返回受影响行数 1（否则默认 0 会被判成版本冲突）
        when(remoteBranchPointer.updateBranchPointer(1L, SHA1, 0)).thenReturn(1);

        Result<RefView> result = remoteService.pushForceUpdateBranch(
                "alice", "test-repo", "master", new RefUpdateRequest(SHA1));

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteBranchPointer, times(1)).updateBranchPointer(1L, SHA1, 0);
        verify(remoteSubmissionRecordMapper, times(1)).insert(any(SubmissionRecord.class));
        verify(remoteCommitPointer, times(1)).insert(any(CommitParent.class));
    }

    /**
     * 强制更新分支：不允许的情况
     */
    @Test
    void forceUpdate_notAllowed_returnsForbidden() {
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteBranchPointer.getBranchPointer("alice", 1L, "master"))
                .thenReturn(buildBranchPointer(0));

        Result<RefView> result = remoteService.pushForceUpdateBranch(
                "alice", "test-repo", "master", new RefUpdateRequest(SHA1));

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
        verify(remoteBranchPointer, never()).updateBranchPointer(anyLong(), anyString(), anyInt());
        verify(remoteSubmissionRecordMapper, never()).insert(any(SubmissionRecord.class));
        verify(remoteCommitPointer, never()).insert(any(CommitParent.class));
    }
}

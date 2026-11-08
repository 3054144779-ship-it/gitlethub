package org.openapitools;

import common.Result;
import common.ResultCode;
import enums.CollaboratorLevelEnum;
import gitlet.commit.Commit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.mapper.*;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.openapitools.utils.Utils;
import org.springframework.test.util.ReflectionTestUtils;
import po.BranchPointer;
import po.Collaborator;
import po.CommitParent;
import po.SubmissionRecord;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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

    @TempDir
    Path tempDir;

    private static final String SHA1 = "c".repeat(40);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
        // pushWrite 会读提交对象建图谱索引，先在磁盘上放一个真实的 Commit 对象
        File commitsDir = tempDir.resolve(".gitlet/alice/test-repo/Objects/commits").toFile();
        commitsDir.mkdirs();
        Utils.writeObject(Utils.join(commitsDir, SHA1), new Commit("force push", null, null, null, Map.of()));
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
        when(remoteBranchPointer.getBranchPointer(1L, "master")).thenReturn(bp);
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
                "alice", "test-repo", "master", new RefUpdateRequest(SHA1), 1L);

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteBranchPointer, times(1)).updateBranchPointer(1L, SHA1, 0);
        verify(remoteSubmissionRecordMapper, times(1)).insertBatch(anyList());
        verify(remoteCommitPointer, times(1)).insert(any(CommitParent.class));
    }

    /**
     * 强制更新分支：不允许的情况
     */
    @Test
    void forceUpdate_notAllowed_returnsForbidden() {
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteBranchPointer.getBranchPointer(1L, "master"))
                .thenReturn(buildBranchPointer(0));
        // 先得通过鉴权，才能走到「分支不允许强推」这一步
        when(remoteCollaborator.getCollaborator(1L, 1L)).thenReturn(
                Collaborator.builder().id(1L).userId(1L).repositoryId(1L)
                        .level(CollaboratorLevelEnum.OWNER).build());

        Result<RefView> result = remoteService.pushForceUpdateBranch(
                "alice", "test-repo", "master", new RefUpdateRequest(SHA1), 1L);

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
        verify(remoteBranchPointer, never()).updateBranchPointer(anyLong(), anyString(), anyInt());
        verify(remoteSubmissionRecordMapper, never()).insert(any(SubmissionRecord.class));
        verify(remoteCommitPointer, never()).insert(any(CommitParent.class));
    }
}

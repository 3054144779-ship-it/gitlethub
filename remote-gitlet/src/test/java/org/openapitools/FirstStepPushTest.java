package org.openapitools;

import common.Result;
import common.ResultCode;
import enums.CollaboratorLevelEnum;
import gitlet.commit.Commit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteCommitPointerMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteSubmissionRecordMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RefUpdateRequest;
import org.openapitools.model.RefView;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import po.BranchPointer;
import po.Collaborator;
import po.CommitParent;
import po.SubmissionRecord;

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

    @MockBean
    private RemoteSubmissionRecordMapper remoteSubmissionRecordMapper;

    @MockBean
    private RemoteCommitPointerMapper remoteCommitPointerMapper;

    @Autowired
    private RemoteServiceImpl remoteService;

    @TempDir
    Path tempDir;

    private static final String SHA1 = "a".repeat(40);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
        // pushUpdateBranch 先校验 sha 存在于对象库，pushWrite 还要读它建图谱索引
        File commitsDir = tempDir.resolve(".gitlet/alice/test-repo/Objects/commits").toFile();
        commitsDir.mkdirs();
        Utils.writeObject(Utils.join(commitsDir, SHA1), new Commit("initial", null, null, null, Map.of()));
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
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteBranchPointerMapper.getBranchPointer(1L, "feature")).thenReturn(bp);
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
        // 乐观锁更新成功：返回受影响行数 1，否则会被判成版本冲突（50001）
        when(remoteBranchPointerMapper.updateBranchPointer(1L, SHA1, 0)).thenReturn(1);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req, 1L);

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteBranchPointerMapper, times(1)).updateBranchPointer(1L, SHA1, 0);
        verify(remoteSubmissionRecordMapper, times(1)).insertBatch(anyList());
        verify(remoteCommitPointerMapper, times(1)).insert(any(CommitParent.class));
    }

    /**
     * 推送分支更新，分支受保护，返回分支受保护
     */
    @Test
    void pushUpdateBranch_protectedBranch_returnsBranchProtected() {
        BranchPointer bp = buildBranchPointer(true);
        // 受保护分支只挡非 OWNER；这里必须是贡献者才会被拦下
        Collaborator collab = buildCollaborator(CollaboratorLevelEnum.CONTRIBUTOR);
        mockCommonDependencies(bp, collab);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req, 1L);

        assertEquals(ResultCode.BRANCH_PROTECTED, result.getCode());
        verify(remoteBranchPointerMapper, never()).updateBranchPointer(anyLong(), anyString(), anyInt());
        verify(remoteSubmissionRecordMapper, never()).insertBatch(anyList());
        verify(remoteCommitPointerMapper, never()).insert(any(CommitParent.class));
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
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req, 1L);

        assertEquals(ResultCode.UNAUTHORIZED, result.getCode());
        verify(remoteBranchPointerMapper, never()).updateBranchPointer(anyLong(), anyString(), anyInt());
        verify(remoteSubmissionRecordMapper, never()).insertBatch(anyList());
        verify(remoteCommitPointerMapper, never()).insert(any(CommitParent.class));
    }

    /**
     * 推送分支更新，协作者不存在，返回未授权
     */
    @Test
    void pushUpdateBranch_nullCollaborator_returnsUnauthorized() {
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        BranchPointer bp = buildBranchPointer(false);
        when(remoteBranchPointerMapper.getBranchPointer(1L, "feature")).thenReturn(bp);
        when(remoteCollaboratorMapper.getCollaborator(1L, 1L)).thenReturn(null);

        RefUpdateRequest req = new RefUpdateRequest(SHA1);
        Result<RefView> result = remoteService.pushUpdateBranch("alice", "test-repo", "feature", req, 1L);

        assertEquals(ResultCode.UNAUTHORIZED, result.getCode());
        verify(remoteBranchPointerMapper, never()).updateBranchPointer(anyLong(), anyString(), anyInt());
        verify(remoteSubmissionRecordMapper, never()).insertBatch(anyList());
        verify(remoteCommitPointerMapper, never()).insert(any(CommitParent.class));
    }
}
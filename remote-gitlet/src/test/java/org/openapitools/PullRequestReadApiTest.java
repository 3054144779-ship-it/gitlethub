package org.openapitools;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import common.Result;
import common.ResultCode;
import dto.PullRequestDTO;
import enums.CollaboratorLevelEnum;
import enums.PRStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.handler.MergeHandler;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.service.impl.PullRequestEventListenerImpl;
import po.Collaborator;
import po.PullRequest;
import po.User;
import vo.PullRequestVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PR 读取 / 关闭的单元测试（DB 层 Mockito 隔离）。
 * 重点覆盖原来的 selectById(repositoryId) 越权 bug。
 */
class PullRequestReadApiTest {

    private static final String OWNER = "alice";
    private static final String REPO = "test-repo";

    @Mock
    private RemotePullRequestMapper remotePullRequestMapper;
    @Mock
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Mock
    private RemoteUserMapper remoteUserMapper;
    @Mock
    private RemoteCollaboratorMapper remoteCollaboratorMapper;
    @Mock
    private MergeHandler mergeHandler;

    @InjectMocks
    private PullRequestEventListenerImpl listener;

    private PullRequest openPr;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(remoteUserMapper.getUserId(OWNER)).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, REPO)).thenReturn(7L);

        openPr = PullRequest.builder()
                .id(100L).repositoryId(7L).title("Add feature")
                .sourceBranch("feature").targetBranch("master")
                .status(PRStatusEnum.OPEN).authorId(1L)
                .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now())
                .isDeleted(0).build();
    }

    @Test
    void getPullRequestById_returnsAuthorName() {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);
        when(remoteUserMapper.selectById(1L)).thenReturn(user(1L, OWNER));

        Result<PullRequestVO> result = listener.getPullRequestById(OWNER, REPO, "100");

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals("Add feature", result.getData().getTitle());
        assertEquals(OWNER, result.getData().getAuthorName());
        assertEquals(PRStatusEnum.OPEN, result.getData().getStatus());
    }

    /** 原 bug：把 repositoryId 当 PR 主键查，会读到别的仓库的 PR */
    @Test
    void getPullRequestById_prOfAnotherRepository_isRejected() {
        PullRequest foreign = PullRequest.builder()
                .id(100L).repositoryId(999L).title("other repo PR")
                .status(PRStatusEnum.OPEN).authorId(1L).build();
        when(remotePullRequestMapper.selectById(100L)).thenReturn(foreign);

        Result<PullRequestVO> result = listener.getPullRequestById(OWNER, REPO, "100");

        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
    }

    @Test
    void getPullRequestById_nonNumericId_returnsFailure() {
        Result<PullRequestVO> result = listener.getPullRequestById(OWNER, REPO, "abc");
        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
    }

    @Test
    void listPullRequests_mapsRecords() {
        Page<PullRequest> page = new Page<>(1, 20);
        page.setRecords(List.of(openPr));
        when(remotePullRequestMapper.selectPage(any(), any())).thenReturn(page);
        when(remoteUserMapper.selectById(1L)).thenReturn(user(1L, OWNER));

        Result<List<PullRequestVO>> result = listener.listPullRequests(OWNER, REPO, 0, 1, 20);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(OWNER, result.getData().get(0).getAuthorName());
    }

    @Test
    void listPullRequests_invalidStatus_returnsFailure() {
        Result<List<PullRequestVO>> result = listener.listPullRequests(OWNER, REPO, 9, 1, 20);
        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
    }

    @Test
    void closePullRequest_setsClosedStatus() {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);

        Result<String> result = listener.closePullRequest(OWNER, REPO, "100", null);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(PRStatusEnum.CLOSED, openPr.getStatus());
        // 一次来自状态更新，一次来自 cleanupStaging 清空暂存区路径
        verify(remotePullRequestMapper, atLeastOnce()).updateById(openPr);
    }

    @Test
    void closePullRequest_alreadyMerged_returnsFailure() {
        openPr.setStatus(PRStatusEnum.MERGED);
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);

        Result<String> result = listener.closePullRequest(OWNER, REPO, "100", null);

        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
        verify(remotePullRequestMapper, never()).updateById(any(PullRequest.class));
    }

    /** 仓库不存在时短路，不去查 PR */
    @Test
    void listPullRequests_unknownRepository_returnsNotFound() {
        when(remoteRepositoryMapper.getRepositoryId(1L, "nope")).thenReturn(null);

        Result<List<PullRequestVO>> result = listener.listPullRequests(OWNER, "nope", null, 1, 20);

        assertEquals(ResultCode.REPOSITORY_NOT_FOUND, result.getCode());
    }

    // ---------- 作者归属：作者应为发起人，而非仓库 owner（原 bug） ----------

    /** 协作者（userId=2）提的 PR，作者必须记成 2，而不是 owner(1) */
    @Test
    void createPullRequest_recordsActorAsAuthorNotOwner() {
        when(remotePullRequestMapper.insert(any(PullRequest.class))).thenReturn(1);

        Result<String> result = listener.createPullRequest(OWNER, REPO, dto(), 2L);

        assertEquals(ResultCode.OK, result.getCode());
        ArgumentCaptor<PullRequest> captor = ArgumentCaptor.forClass(PullRequest.class);
        verify(remotePullRequestMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getAuthorId());
    }

    /** 未携带身份（无过滤器/未登录）时回退为仓库 owner，保持旧行为 */
    @Test
    void createPullRequest_withoutActor_fallsBackToOwner() {
        when(remotePullRequestMapper.insert(any(PullRequest.class))).thenReturn(1);

        listener.createPullRequest(OWNER, REPO, dto(), null);

        ArgumentCaptor<PullRequest> captor = ArgumentCaptor.forClass(PullRequest.class);
        verify(remotePullRequestMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getAuthorId());
    }

    // ---------- 合并权限：仅仓库 OWNER 可合并 ----------

    /** 协作者即便提了 PR 也不能自己合并 */
    @Test
    void mergePullRequest_contributor_returnsForbidden() {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);
        when(remoteCollaboratorMapper.getCollaborator(2L, 7L))
                .thenReturn(collaborator(2L, 7L, CollaboratorLevelEnum.CONTRIBUTOR));

        Result<String> result = listener.mergePullRequest(OWNER, REPO, "100", 2L);

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
        verify(mergeHandler, never()).merge(anyLong(), anyString(), anyString(), anyLong());
        verify(remotePullRequestMapper, never()).updateById(any(PullRequest.class));
    }

    /** 无身份（未登录）一律无权合并 */
    @Test
    void mergePullRequest_withoutActor_returnsForbidden() {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);

        Result<String> result = listener.mergePullRequest(OWNER, REPO, "100", null);

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
        verify(mergeHandler, never()).merge(anyLong(), anyString(), anyString(), anyLong());
    }

    /** owner 合并成功 → PR 状态置为 MERGED */
    @Test
    void mergePullRequest_owner_succeeds() {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);
        when(remoteCollaboratorMapper.getCollaborator(1L, 7L))
                .thenReturn(collaborator(1L, 7L, CollaboratorLevelEnum.OWNER));
        when(mergeHandler.merge(7L, "feature", "master", 100L)).thenReturn(Result.ok());

        Result<String> result = listener.mergePullRequest(OWNER, REPO, "100", 1L);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(PRStatusEnum.MERGED, openPr.getStatus());
    }

    /** 被逻辑删除的 owner 行不算 owner，避免"移除后仍可合并" */
    @Test
    void mergePullRequest_removedOwner_returnsForbidden() {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);
        Collaborator removed = collaborator(1L, 7L, CollaboratorLevelEnum.OWNER);
        removed.setIsDeleted(1);
        when(remoteCollaboratorMapper.getCollaborator(1L, 7L)).thenReturn(removed);

        Result<String> result = listener.mergePullRequest(OWNER, REPO, "100", 1L);

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
    }

    /** PR 属于别的仓库时不可通过本仓库 URL 合并 */
    @Test
    void mergePullRequest_prOfAnotherRepository_isRejected() {
        PullRequest foreign = PullRequest.builder()
                .id(100L).repositoryId(999L).status(PRStatusEnum.OPEN).authorId(1L).build();
        when(remotePullRequestMapper.selectById(100L)).thenReturn(foreign);

        Result<String> result = listener.mergePullRequest(OWNER, REPO, "100", 1L);

        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
        verify(mergeHandler, never()).merge(anyLong(), anyString(), anyString(), anyLong());
    }

    private PullRequestDTO dto() {
        PullRequestDTO dto = new PullRequestDTO();
        dto.setTitle("t");
        dto.setDescription("d");
        dto.setSourceBranch("feature");
        dto.setTargetBranch("master");
        return dto;
    }

    private Collaborator collaborator(Long userId, Long repositoryId, CollaboratorLevelEnum level) {
        return Collaborator.builder()
                .userId(userId).repositoryId(repositoryId).level(level).isDeleted(0).build();
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}

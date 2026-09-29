package org.openapitools;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import common.Result;
import common.ResultCode;
import enums.PRStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.handler.MergeHandler;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.service.impl.PullRequestEventListenerImpl;
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

        Result<String> result = listener.closePullRequest(OWNER, REPO, "100");

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(PRStatusEnum.CLOSED, openPr.getStatus());
        // 一次来自状态更新，一次来自 cleanupStaging 清空暂存区路径
        verify(remotePullRequestMapper, atLeastOnce()).updateById(openPr);
    }

    @Test
    void closePullRequest_alreadyMerged_returnsFailure() {
        openPr.setStatus(PRStatusEnum.MERGED);
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPr);

        Result<String> result = listener.closePullRequest(OWNER, REPO, "100");

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

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}

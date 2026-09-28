package org.openapitools;

import common.Result;
import common.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.RefView;
import org.openapitools.service.impl.RemoteServiceImpl;
import po.BranchPointer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * getBranchInformation 单元测试：分支指向已迁移到数据库，
 * 因此用 Mockito 隔离 Mapper，不再依赖文件系统。
 */
class GetBranchInformationTest {

    @Mock
    private RemoteUserMapper remoteUserMapper;
    @Mock
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Mock
    private RemoteBranchPointerMapper remoteBranchPointer;

    @InjectMocks
    private RemoteServiceImpl remoteService;

    private static final String SHA1 = "a".repeat(40);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * 分支存在
     */
    @Test
    void getBranchInformation_branchExists_returnsSha() {
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteBranchPointer.getBranchPointer(1L, "master")).thenReturn(
                BranchPointer.builder()
                        .id(1L)
                        .repositoryId(1L)
                        .branchName("master")
                        .commitSha(SHA1)
                        .build());

        Result<RefView> result = remoteService.getBranchInformation("alice", "test-repo", "master");

        assertEquals(ResultCode.OK, result.getCode());
        assertNotNull(result.getData());
        assertEquals("master", result.getData().getBranch());
        assertEquals(SHA1, result.getData().getSha());
    }

    /**
     * 分支不存在
     */
    @Test
    void getBranchInformation_branchNotExist_returnsBranchNotFound() {
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, "test-repo")).thenReturn(1L);
        when(remoteBranchPointer.getBranchPointer(1L, "dev")).thenReturn(null);

        Result<RefView> result = remoteService.getBranchInformation("alice", "test-repo", "dev");

        assertEquals(ResultCode.BRANCH_NOT_FOUND_REFS, result.getCode());
    }
}

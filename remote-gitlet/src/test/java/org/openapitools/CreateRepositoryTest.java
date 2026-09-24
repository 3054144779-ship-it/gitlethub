package org.openapitools;

import common.Result;
import common.ResultCode;
import dto.RepositoryCreateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import po.BranchPointer;
import po.Repository;

@SpringBootTest
public class CreateRepositoryTest {

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

    /**
     * 初始化测试环境
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
    }

    /**
     * 创建仓库成功测试
     */
    @Test
    void createRepository_success() {
        when(remoteUserMapper.getUserId("alice")).thenReturn(1L);
        when(remoteRepositoryMapper.insert(any(Repository.class))).thenReturn(1);
        when(remoteBranchPointerMapper.insert(any(BranchPointer.class))).thenReturn(1);

        RepositoryCreateDTO dto = new RepositoryCreateDTO();
        dto.setOwner("alice");
        dto.setRepo("test-repo");
        dto.setDescription("A test repository");

        Result<ObjectUploadResult> result = remoteService.createRemoteRepository(dto);

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteRepositoryMapper, times(1)).insert(any(Repository.class));
        verify(remoteBranchPointerMapper, times(1)).insert(any(BranchPointer.class));

        // 获取仓库目录
        File repoDir = tempDir.resolve(".gitlet/alice/test-repo").toFile();
        // 验证仓库目录是否存在
        assertTrue(repoDir.exists());
        assertTrue(repoDir.isDirectory());
    }

    /**
     * 创建仓库重复性测试
     */
    @Test
    void createRepository_duplicate_returnsAlreadyExists() {
        File repoDir = tempDir.resolve(".gitlet/alice/test-repo").toFile();
        repoDir.mkdirs();

        RepositoryCreateDTO dto = new RepositoryCreateDTO();
        dto.setOwner("alice");
        dto.setRepo("test-repo");
        dto.setDescription("A test repository");

        Result<ObjectUploadResult> result = remoteService.createRemoteRepository(dto);

        assertEquals(ResultCode.REPOSITORY_ALREADY_EXISTS, result.getCode());
        verify(remoteRepositoryMapper, never()).insert(any(Repository.class));
        verify(remoteBranchPointerMapper, never()).insert(any(BranchPointer.class));
    }
}
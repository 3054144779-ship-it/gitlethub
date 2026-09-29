package org.openapitools;

import common.Result;
import common.ResultCode;
import dto.CollaboratorCreateDTO;
import enums.CollaboratorLevelEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.service.impl.RepositoryServiceImpl;
import po.Collaborator;
import po.Repository;
import po.User;
import vo.CollaboratorVO;
import vo.RepositoryVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 仓库按 owner/repo 读取 + 协作者增删的单元测试（DB 层 Mockito 隔离）。
 */
class RepositoryReadApiTest {

    private static final String OWNER = "alice";
    private static final String REPO = "test-repo";

    @Mock
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Mock
    private RemoteUserMapper remoteUserMapper;
    @Mock
    private RemoteCollaboratorMapper remoteCollaboratorMapper;

    @InjectMocks
    private RepositoryServiceImpl repositoryService;

    private Repository repository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        repository = Repository.builder()
                .id(7L).ownerId(1L).name(REPO).description("d")
                .visibility(0).defaultBranch("master").allowAutoPr(1).build();

        when(remoteUserMapper.getUserId(OWNER)).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, REPO)).thenReturn(7L);
        when(remoteRepositoryMapper.selectById(7L)).thenReturn(repository);
    }

    @Test
    void getRepositoryByOwnerAndRepo_returnsOwnerName() {
        Result<RepositoryVO> result = repositoryService.getRepositoryByOwnerAndRepo(OWNER, REPO);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(7L, result.getData().getId());
        assertEquals(OWNER, result.getData().getOwnerName());
        assertEquals(REPO, result.getData().getName());
    }

    @Test
    void getRepositoryByOwnerAndRepo_unknownRepo_returnsNotFound() {
        when(remoteRepositoryMapper.getRepositoryId(1L, "nope")).thenReturn(null);

        Result<RepositoryVO> result = repositoryService.getRepositoryByOwnerAndRepo(OWNER, "nope");

        assertEquals(ResultCode.REPOSITORY_NOT_FOUND, result.getCode());
    }

    @Test
    void listCollaborators_joinsUsername() {
        when(remoteCollaboratorMapper.selectList(any())).thenReturn(List.of(
                Collaborator.builder().id(1L).repositoryId(7L).userId(1L)
                        .level(CollaboratorLevelEnum.OWNER).autoCreatePr(1)
                        .createTime(LocalDateTime.now()).build()));
        when(remoteUserMapper.selectById(1L)).thenReturn(user(1L, OWNER));

        Result<List<CollaboratorVO>> result = repositoryService.listCollaborators(OWNER, REPO);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(OWNER, result.getData().get(0).getUsername());
        assertEquals(CollaboratorLevelEnum.OWNER, result.getData().get(0).getLevel());
    }

    @Test
    void addCollaborator_byNonOwner_isForbidden() {
        // 调用者是 CONTRIBUTOR，不是 OWNER
        when(remoteCollaboratorMapper.getCollaborator(1L, 7L)).thenReturn(
                Collaborator.builder().id(1L).userId(1L).repositoryId(7L)
                        .level(CollaboratorLevelEnum.CONTRIBUTOR).build());

        Result<String> result = repositoryService.addCollaborator(OWNER, REPO, dto("bob", 1));

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
        verify(remoteCollaboratorMapper, never()).insert(any(Collaborator.class));
    }

    @Test
    void addCollaborator_ownerAddsNewUser_inserts() {
        stubCallerIsOwner();
        when(remoteUserMapper.getUserId("bob")).thenReturn(2L);
        when(remoteCollaboratorMapper.getCollaborator(2L, 7L)).thenReturn(null);

        Result<String> result = repositoryService.addCollaborator(OWNER, REPO, dto("bob", 1));

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteCollaboratorMapper).insert(any(Collaborator.class));
    }

    /** 被移除过的协作者（逻辑删除行仍在）重新添加时应复活而不是再插一行 */
    @Test
    void addCollaborator_existingSoftDeletedRow_reactivates() {
        stubCallerIsOwner();
        when(remoteUserMapper.getUserId("bob")).thenReturn(2L);
        when(remoteCollaboratorMapper.getCollaborator(2L, 7L)).thenReturn(
                Collaborator.builder().id(9L).userId(2L).repositoryId(7L)
                        .level(CollaboratorLevelEnum.GUEST).isDeleted(1).build());

        Result<String> result = repositoryService.addCollaborator(OWNER, REPO, dto("bob", 1));

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteCollaboratorMapper).reactivateCollaborator(2L, 7L, 1);
        verify(remoteCollaboratorMapper, never()).insert(any(Collaborator.class));
    }

    @Test
    void addCollaborator_unknownUsername_returnsUserNotFound() {
        stubCallerIsOwner();
        when(remoteUserMapper.getUserId("ghost")).thenReturn(null);

        Result<String> result = repositoryService.addCollaborator(OWNER, REPO, dto("ghost", 1));

        assertEquals(ResultCode.USER_NOT_FOUND, result.getCode());
    }

    /** 不允许通过该接口再授予一个 OWNER */
    @Test
    void addCollaborator_ownerLevelIsRejected() {
        stubCallerIsOwner();

        Result<String> result = repositoryService.addCollaborator(OWNER, REPO, dto("bob", 2));

        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
    }

    @Test
    void removeCollaborator_deletesContributor() {
        stubCallerIsOwner();
        when(remoteCollaboratorMapper.getCollaborator(2L, 7L)).thenReturn(
                Collaborator.builder().id(9L).userId(2L).repositoryId(7L)
                        .level(CollaboratorLevelEnum.CONTRIBUTOR).isDeleted(0).build());

        Result<String> result = repositoryService.removeCollaborator(OWNER, REPO, 2L);

        assertEquals(ResultCode.OK, result.getCode());
        verify(remoteCollaboratorMapper).deleteById(9L);
    }

    @Test
    void removeCollaborator_ownerCannotBeRemoved() {
        stubCallerIsOwner();
        when(remoteCollaboratorMapper.getCollaborator(1L, 7L)).thenReturn(
                Collaborator.builder().id(1L).userId(1L).repositoryId(7L)
                        .level(CollaboratorLevelEnum.OWNER).isDeleted(0).build());

        Result<String> result = repositoryService.removeCollaborator(OWNER, REPO, 1L);

        assertEquals(ResultCode.FORBIDDEN, result.getCode());
        verify(remoteCollaboratorMapper, never()).deleteById(any(Long.class));
    }

    private void stubCallerIsOwner() {
        when(remoteCollaboratorMapper.getCollaborator(1L, 7L)).thenReturn(
                Collaborator.builder().id(1L).userId(1L).repositoryId(7L)
                        .level(CollaboratorLevelEnum.OWNER).build());
    }

    private CollaboratorCreateDTO dto(String username, Integer level) {
        CollaboratorCreateDTO dto = new CollaboratorCreateDTO();
        dto.setUsername(username);
        dto.setLevel(level);
        return dto;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}

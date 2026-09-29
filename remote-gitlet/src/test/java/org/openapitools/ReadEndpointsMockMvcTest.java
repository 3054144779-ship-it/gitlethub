package org.openapitools;

import enums.CollaboratorLevelEnum;
import enums.PRStatusEnum;
import gitlet.commit.Commit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.mapper.*;
import org.openapitools.service.support.RepositoryObjectReader;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import po.BranchPointer;
import po.Collaborator;
import po.PullRequest;
import po.Repository;
import po.User;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 新增读取接口的 HTTP 级验证：路径映射、参数绑定、Result 外层、以及前端依赖的 JSON 字段名
 * （枚举序列化成枚举名、BlobVO 的 isBinary 键）。
 * DB 层全部 @MockBean，仓库对象用 @TempDir 里的真实提交/blob 文件。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadEndpointsMockMvcTest {

    private static final String OWNER = "alice";
    private static final String REPO = "test-repo";
    private static final String BLOB_SHA = "a".repeat(40);
    private static final String COMMIT_SHA = "b".repeat(40);

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
    @MockBean
    private RemotePullRequestMapper remotePullRequestMapper;

    @Autowired
    private RepositoryObjectReader repositoryObjectReader;

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    private static final String BASE = "/api/repos/" + OWNER + "/" + REPO;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(repositoryObjectReader, "basePath", tempDir.toString());

        // 磁盘对象：一个提交（含一个文本文件）+ 一个 blob
        File commitsDir = tempDir.resolve(".gitlet/" + OWNER + "/" + REPO + "/Objects/commits").toFile();
        File objectsDir = tempDir.resolve(".gitlet/" + OWNER + "/" + REPO + "/Objects").toFile();
        commitsDir.mkdirs();
        Utils.writeObject(Utils.join(commitsDir, COMMIT_SHA),
                new Commit("initial commit", null, null, null, Map.of("a.txt", BLOB_SHA)));
        Utils.writeContents(Utils.join(objectsDir, BLOB_SHA), "hello 世界".getBytes(StandardCharsets.UTF_8));

        // DB：用户名、仓库、分支、协作者、用户详情
        when(remoteUserMapper.getUserId(OWNER)).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, REPO)).thenReturn(7L);
        when(remoteRepositoryMapper.selectById(7L)).thenReturn(
                Repository.builder().id(7L).ownerId(1L).name(REPO).description("demo")
                        .visibility(0).defaultBranch("master").allowAutoPr(1).build());
        when(remoteBranchPointer.getBranchPointer(7L, "master")).thenReturn(
                BranchPointer.builder().id(3L).repositoryId(7L).branchName("master")
                        .commitSha(COMMIT_SHA).isDefault(1).isProtected(1).version(0).build());
        when(remoteBranchPointer.selectList(any())).thenReturn(List.of(
                BranchPointer.builder().id(3L).repositoryId(7L).branchName("master")
                        .commitSha(COMMIT_SHA).isDefault(1).isProtected(1).version(0).build()));
        when(remoteCollaborator.getCollaborator(1L, 7L)).thenReturn(
                Collaborator.builder().id(1L).repositoryId(7L).userId(1L)
                        .level(CollaboratorLevelEnum.OWNER).autoCreatePr(1).build());
        when(remoteCollaborator.selectList(any())).thenReturn(List.of(
                Collaborator.builder().id(1L).repositoryId(7L).userId(1L)
                        .level(CollaboratorLevelEnum.OWNER).autoCreatePr(1).build()));
        when(remoteUserMapper.selectById(1L)).thenReturn(user(1L, OWNER));
    }

    @Test
    void repoByOwnerAndRepo() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value(REPO))
                .andExpect(jsonPath("$.data.ownerName").value(OWNER));
    }

    @Test
    void branches() throws Exception {
        mockMvc.perform(get(BASE + "/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].branchName").value("master"))
                .andExpect(jsonPath("$.data[0].commitSha").value(COMMIT_SHA));
    }

    @Test
    void commitsAndCommitDetail() throws Exception {
        mockMvc.perform(get(BASE + "/commits").param("branch", "master"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].sha").value(COMMIT_SHA))
                .andExpect(jsonPath("$.data[0].message").value("initial commit"));

        mockMvc.perform(get(BASE + "/commits/" + COMMIT_SHA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackedFiles['a.txt']").value(BLOB_SHA));
    }

    @Test
    void commitDiff() throws Exception {
        mockMvc.perform(get(BASE + "/commits/" + COMMIT_SHA + "/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].path").value("a.txt"))
                .andExpect(jsonPath("$.data[0].changeType").value("ADDED"));
    }

    @Test
    void treeAndBlob() throws Exception {
        mockMvc.perform(get(BASE + "/tree").param("ref", "master"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].path").value("a.txt"))
                .andExpect(jsonPath("$.data[0].blobSha").value(BLOB_SHA));

        mockMvc.perform(get(BASE + "/blob").param("ref", "master").param("path", "a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blobSha").value(BLOB_SHA))
                // 前端读的键就是 isBinary（Lombok 给 Boolean 字段生成 getIsBinary）
                .andExpect(jsonPath("$.data.isBinary").value(false))
                .andExpect(jsonPath("$.data.content").value("hello 世界"));
    }

    @Test
    void pullRequestListDetailAndClose() throws Exception {
        PullRequest pr = PullRequest.builder()
                .id(100L).repositoryId(7L).title("Add feature")
                .sourceBranch("feature").targetBranch("master")
                .status(PRStatusEnum.OPEN).authorId(1L).build();
        when(remotePullRequestMapper.selectById(100L)).thenReturn(pr);
        when(remotePullRequestMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<PullRequest> page =
                    inv.getArgument(0);
            page.setRecords(List.of(pr));
            return page;
        });

        mockMvc.perform(get(BASE + "/pull-requests").param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(100))
                // 枚举名序列化，前端 dict 已兼容 OPEN/CLOSED/MERGED
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[0].authorName").value(OWNER));

        mockMvc.perform(get(BASE + "/pull-request/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Add feature"));

        mockMvc.perform(post(BASE + "/pull-request/100/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void collaboratorsListAddRemove() throws Exception {
        when(remoteUserMapper.getUserId("bob")).thenReturn(2L);
        when(remoteCollaborator.getCollaborator(2L, 7L)).thenReturn(
                Collaborator.builder().id(9L).repositoryId(7L).userId(2L)
                        .level(CollaboratorLevelEnum.CONTRIBUTOR).isDeleted(0).build());

        mockMvc.perform(get(BASE + "/collaborators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value(OWNER))
                // 等级同样是枚举名
                .andExpect(jsonPath("$.data[0].level").value("OWNER"));

        mockMvc.perform(post(BASE + "/collaborators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"level\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(delete(BASE + "/collaborators/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void commitOfUnknownRepoReturnsErrorEnvelope() throws Exception {
        when(remoteRepositoryMapper.getRepositoryId(1L, "missing")).thenReturn(null);

        mockMvc.perform(get("/api/repos/" + OWNER + "/missing/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(30001));
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}

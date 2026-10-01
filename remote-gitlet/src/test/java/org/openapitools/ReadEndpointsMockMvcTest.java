package org.openapitools;

import cn.hutool.jwt.JWT;
import common.Result;
import enums.CollaboratorLevelEnum;
import enums.PRStatusEnum;
import gitlet.commit.Commit;
import jwt.KeyPairManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.handler.MergeHandler;
import org.openapitools.mapper.*;
import org.openapitools.service.support.RepositoryObjectReader;
import org.openapitools.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
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
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    // 合并要真写对象存储，这里只验证鉴权链路，故 mock 掉
    @MockBean
    private MergeHandler mergeHandler;
    // 鉴权依赖：签名校验用 mock，token 本体用 hutool 真实签出以走真实解析
    @MockBean
    private KeyPairManager keyPairManager;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

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

        // JwtAuthFilter：签名一律通过、token 不在登出黑名单
        when(keyPairManager.verifyToken(anyString())).thenReturn(true);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
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

        // 增删协作者要判「调用者是不是 OWNER」，身份来自 token，必须带上 Bearer
        mockMvc.perform(post(BASE + "/collaborators")
                        .header(HttpHeaders.AUTHORIZATION, bearer(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"level\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(delete(BASE + "/collaborators/2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(1L)))
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

    /**
     * 鉴权链路端到端：JwtAuthFilter 解析 Bearer token → 写入 request attribute
     * → controller 的 @RequestAttribute 绑定 → service 判定身份。携带 owner 的 token 可合并。
     */
    @Test
    void mergePullRequest_withOwnerToken_succeeds() throws Exception {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPrWithAuthor(1L));
        when(mergeHandler.merge(7L, "feature", "master", 100L)).thenReturn(Result.ok());

        mockMvc.perform(post(BASE + "/pull-request/100")
                        .header(HttpHeaders.AUTHORIZATION, bearer(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 协作者（CONTRIBUTOR）的 token 无法合并 —— 即便 PR 是他自己提的 */
    @Test
    void mergePullRequest_withContributorToken_isForbidden() throws Exception {
        when(remotePullRequestMapper.selectById(100L)).thenReturn(openPrWithAuthor(2L));
        when(remoteCollaborator.getCollaborator(2L, 7L)).thenReturn(
                Collaborator.builder().id(9L).repositoryId(7L).userId(2L)
                        .level(CollaboratorLevelEnum.CONTRIBUTOR).isDeleted(0).build());

        mockMvc.perform(post(BASE + "/pull-request/100")
                        .header(HttpHeaders.AUTHORIZATION, bearer(2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20002));
    }

    private PullRequest openPrWithAuthor(Long authorId) {
        return PullRequest.builder()
                .id(100L).repositoryId(7L).title("Add feature")
                .sourceBranch("feature").targetBranch("master")
                .status(PRStatusEnum.OPEN).authorId(authorId).build();
    }

    /** 用 hutool 真实签出带 userId claim 的 Bearer 值，供过滤器解析 */
    private String bearer(Long userId) {
        String token = JWT.create()
                .setPayload("userId", userId)
                .setExpiresAt(new Date(System.currentTimeMillis() + 3600_000L))
                .setKey("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .sign();
        return "Bearer " + token;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}

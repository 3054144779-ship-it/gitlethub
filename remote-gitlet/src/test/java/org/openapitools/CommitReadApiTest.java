package org.openapitools;

import common.Result;
import common.ResultCode;
import gitlet.commit.Commit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openapitools.mapper.RemoteBranchPointerMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteSubmissionRecordMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.service.impl.RemoteServiceImpl;
import org.openapitools.utils.Utils;
import org.springframework.test.util.ReflectionTestUtils;
import po.BranchPointer;
import po.Repository;
import vo.BlobVO;
import vo.CommitVO;
import vo.FileChangeVO;
import vo.FileEntryVO;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 历史遍历 / 文件树 / 文件内容 / diff 的读取测试。
 * 提交对象用真实文件（@TempDir）落盘，DB 层用 Mockito 隔离。
 */
class CommitReadApiTest {

    private static final String OWNER = "alice";
    private static final String REPO = "test-repo";

    @Mock
    private RemoteUserMapper remoteUserMapper;
    @Mock
    private RemoteRepositoryMapper remoteRepositoryMapper;
    @Mock
    private RemoteBranchPointerMapper remoteBranchPointer;
    @Mock
    private RemoteSubmissionRecordMapper remoteSubmissionRecordMapper;

    @InjectMocks
    private RemoteServiceImpl remoteService;

    @TempDir
    Path tempDir;

    /** 提交目录 {tmp}/.gitlet/alice/test-repo/Objects/commits */
    private File commitsDir;
    /** blob 目录 {tmp}/.gitlet/alice/test-repo/Objects */
    private File objectsDir;

    private String root;
    private String main2;
    private String main3;
    private String feat1;
    private String merge;

    private static final String BLOB_A = "a".repeat(40);
    private static final String BLOB_B = "b".repeat(40);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(remoteService, "basePath", tempDir.toString());
        commitsDir = tempDir.resolve(".gitlet/" + OWNER + "/" + REPO + "/Objects/commits").toFile();
        objectsDir = tempDir.resolve(".gitlet/" + OWNER + "/" + REPO + "/Objects").toFile();
        assertTrue(commitsDir.mkdirs());

        when(remoteUserMapper.getUserId(OWNER)).thenReturn(1L);
        when(remoteRepositoryMapper.getRepositoryId(1L, REPO)).thenReturn(1L);
        when(remoteRepositoryMapper.selectById(1L)).thenReturn(
                Repository.builder().id(1L).ownerId(1L).name(REPO).defaultBranch("master").build());

        // 构造 DAG：root <- main2 <- main3（master tip）
        //                  \-- feat1 --/  merge 的第一父是 main3、第二父是 feat1
        root = writeCommit("root", null, null, Map.of("a.txt", BLOB_A));
        main2 = writeCommit("main2", root, null, Map.of("a.txt", BLOB_A));
        main3 = writeCommit("main3", main2, null, Map.of("a.txt", BLOB_A, "b.txt", BLOB_B));
        feat1 = writeCommit("feat1", root, null, Map.of("a.txt", BLOB_A));
        merge = writeCommit("merge", main3, feat1, Map.of("a.txt", BLOB_A, "b.txt", BLOB_B));

        // 分支指针：master 未动，merged 指向 merge
        when(remoteBranchPointer.getBranchPointer(1L, "master")).thenReturn(
                BranchPointer.builder().id(1L).repositoryId(1L).branchName("master")
                        .commitSha(main3).isDefault(1).build());
        when(remoteBranchPointer.getBranchPointer(1L, "merged")).thenReturn(
                BranchPointer.builder().id(2L).repositoryId(1L).branchName("merged")
                        .commitSha(merge).isDefault(0).build());
    }

    /**
     * 合并 DAG：每个提交只出现一次，且主链先于第二父被遍历
     */
    @Test
    void listCommits_walksMergeDagWithoutDuplicates() {
        Result<List<CommitVO>> result =
                remoteService.listCommits(OWNER, REPO, "merged", 1, 100);

        assertEquals(ResultCode.OK, result.getCode());
        List<String> shas = result.getData().stream().map(CommitVO::getSha).toList();
        assertEquals(List.of(merge, main3, main2, root, feat1), shas);
    }

    /**
     * branch 缺省时用仓库默认分支
     */
    @Test
    void listCommits_defaultsToRepositoryDefaultBranch() {
        Result<List<CommitVO>> result = remoteService.listCommits(OWNER, REPO, null, null, null);

        assertEquals(ResultCode.OK, result.getCode());
        List<String> shas = result.getData().stream().map(CommitVO::getSha).toList();
        assertEquals(List.of(main3, main2, root), shas);
    }

    /**
     * 内存分页
     */
    @Test
    void listCommits_paginatesInMemory() {
        List<CommitVO> page2 = remoteService.listCommits(OWNER, REPO, "merged", 2, 2).getData();
        assertEquals(List.of(main2, root), page2.stream().map(CommitVO::getSha).toList());

        List<CommitVO> page3 = remoteService.listCommits(OWNER, REPO, "merged", 3, 2).getData();
        assertEquals(List.of(feat1), page3.stream().map(CommitVO::getSha).toList());

        // 越界页返回空列表而不是报错
        assertTrue(remoteService.listCommits(OWNER, REPO, "merged", 99, 2).getData().isEmpty());
    }

    /**
     * 提交详情带 trackedFiles；列表不带
     */
    @Test
    void getCommit_returnsTrackedFiles() {
        Result<CommitVO> result = remoteService.getCommit(OWNER, REPO, main3);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals("main3", result.getData().getMessage());
        assertEquals(2, result.getData().getTrackedFiles().size());
        assertNull(remoteService.listCommits(OWNER, REPO, "master", 1, 1).getData().get(0).getTrackedFiles());
    }

    /**
     * 不存在的提交返回错误码
     */
    @Test
    void getCommit_missingSha_returnsFailure() {
        Result<CommitVO> result = remoteService.getCommit(OWNER, REPO, "0".repeat(40));
        assertEquals(ResultCode.SHA1_MISMATCH, result.getCode());
    }

    /**
     * ref 可以是分支名，也可以是 SHA
     */
    @Test
    void listTree_acceptsBranchNameOrSha() {
        Result<List<FileEntryVO>> byBranch = remoteService.listTree(OWNER, REPO, "merged");
        Result<List<FileEntryVO>> bySha = remoteService.listTree(OWNER, REPO, merge);

        assertEquals(ResultCode.OK, byBranch.getCode());
        assertEquals(List.of("a.txt", "b.txt"), byBranch.getData().stream().map(FileEntryVO::getPath).toList());
        assertEquals(byBranch.getData(), bySha.getData());
    }

    /**
     * 文本按 UTF-8 返回；含 NUL 的按二进制标记且不回内容
     */
    @Test
    void getBlob_returnsTextAndFlagsBinary() {
        Utils.writeContents(Utils.join(objectsDir, BLOB_A), "hello 世界");
        Utils.writeContents(Utils.join(objectsDir, BLOB_B), new byte[] {'a', 0, 'b'});

        Result<BlobVO> text = remoteService.getBlob(OWNER, REPO, "master", "a.txt");
        assertEquals(ResultCode.OK, text.getCode());
        assertFalse(text.getData().getIsBinary());
        assertEquals("hello 世界", text.getData().getContent());

        Result<BlobVO> binary = remoteService.getBlob(OWNER, REPO, "master", "b.txt");
        assertEquals(ResultCode.OK, binary.getCode());
        assertTrue(binary.getData().getIsBinary());
        assertEquals("", binary.getData().getContent());
    }

    /**
     * 不存在的文件路径返回错误码
     */
    @Test
    void getBlob_missingPath_returnsFailure() {
        Result<BlobVO> result = remoteService.getBlob(OWNER, REPO, "master", "nope.txt");
        assertEquals(ResultCode.BAD_REQUEST, result.getCode());
    }

    /**
     * diff 与第一父比较：root 之后新增 b.txt，其余 a.txt 未变
     */
    @Test
    void diffCommit_comparesWithFirstParent() {
        Utils.writeContents(Utils.join(objectsDir, BLOB_A), "hello");
        Utils.writeContents(Utils.join(objectsDir, BLOB_B), "world");

        Result<List<FileChangeVO>> result = remoteService.diffCommit(OWNER, REPO, main3);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(1, result.getData().size());
        FileChangeVO change = result.getData().get(0);
        assertEquals("b.txt", change.getPath());
        assertEquals("ADDED", change.getChangeType());
        assertEquals("world", change.getNewContent());
    }

    /**
     * 根提交的全部文件都算新增
     */
    @Test
    void diffCommit_rootTreatsEverythingAsAdded() {
        Utils.writeContents(Utils.join(objectsDir, BLOB_A), "hello");

        Result<List<FileChangeVO>> result = remoteService.diffCommit(OWNER, REPO, root);

        assertEquals(ResultCode.OK, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("ADDED", result.getData().get(0).getChangeType());
    }

    /** 把提交对象写到磁盘，返回其 SHA（直接用固定字符串当文件名） */
    private String writeCommit(String message, String parent, String secondParent,
                               Map<String, String> tracked) {
        String sha = Utils.sha1(message + "|" + parent + "|" + secondParent + "|" + tracked.size());
        Utils.writeObject(Utils.join(commitsDir, sha),
                new Commit(message, null, parent, secondParent, tracked));
        return sha;
    }
}

package org.openapitools;

import gitlet.commit.Commit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.utils.Utils;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 保证服务端写出的 commit 字节流与 CLI（gitlet_core）兼容：
 * 类名必须是 {@code gitlet.commit.Commit}，字段与 serialVersionUID 一致。
 * 同时验证反序列化白名单会拒绝非 Commit 的类。
 */
class CommitSerializationCompatTest {

    @TempDir
    Path tempDir;

    /**
     * 写出再读回，字段一致（这就是 CLI 与服务端之间实际走的路径）
     */
    @Test
    void commitRoundTripKeepsAllFields() {
        Commit original = new Commit("initial commit", List.of("a.txt"),
                "p".repeat(40), "q".repeat(40),
                Map.of("a.txt", "1".repeat(40), "dir/b.txt", "2".repeat(40)));

        File file = tempDir.resolve("commit").toFile();
        Utils.writeObject(file, original);

        Commit restored = Utils.readObject(file, Commit.class);

        assertEquals(original.getMessage(), restored.getMessage());
        assertEquals(original.getTime(), restored.getTime());
        assertEquals(original.getParent(), restored.getParent());
        assertEquals(original.getSecondParent(), restored.getSecondParent());
        assertEquals(original.getMergeFiles(), restored.getMergeFiles());
        assertEquals(original.getTrackedFiles(), restored.getTrackedFiles());
    }

    /**
     * 类名必须是 CLI 端的 gitlet.commit.Commit，否则客户端反序列化报 ClassNotFoundException
     */
    @Test
    void serializedNameMatchesCliPackage() {
        Commit commit = new Commit("m", null, null, null, Map.of());
        byte[] bytes = Utils.serialize(commit);
        String asText = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(asText.contains("gitlet.commit.Commit"),
                "序列化流中应包含 gitlet.commit.Commit 类名");
        assertFalse(asText.contains("org.openapitools.model.Commit"),
                "不应再写出旧的 org.openapitools.model.Commit 类名");
    }

    /**
     * 白名单外的类必须被拒绝（对象字节来自客户端 push，属于不可信输入）
     */
    @Test
    void readObjectRejectsNonAllowlistedClass() {
        File file = tempDir.resolve("evil").toFile();
        Utils.writeObject(file, new NotAllowed());

        assertThrows(IllegalArgumentException.class,
                () -> Utils.readObject(file, Serializable.class));
    }

    /** 不在白名单里的自定义类 */
    private static class NotAllowed implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private String payload = "boom";
    }
}

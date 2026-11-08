package org.openapitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 暂存区删除清单：记录用户在冲突解决时选择「删除」的文件路径。
 * 清单是暂存区根目录下的明文文件，一行一个相对路径；暂存区清理时随之删除。
 */
public final class StagingTombstone {

    public static final String FILE_NAME = ".gitlet-deletions";

    private StagingTombstone() {
    }

    public static File fileOf(Path stagingRoot) {
        return stagingRoot.resolve(FILE_NAME).toFile();
    }

    /** 读取删除清单；文件不存在或读失败时返回空集合（删除信息缺失不应阻断合并） */
    public static Set<String> read(Path stagingRoot) {
        Set<String> paths = new LinkedHashSet<>();
        File file = fileOf(stagingRoot);
        if (!file.isFile()) {
            return paths;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String path = line.trim();
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            // 读失败按「没有删除」处理，避免暂存区文件损坏导致合并直接失败
            return new LinkedHashSet<>();
        }
        return paths;
    }

    public static void write(Path stagingRoot, Set<String> paths) throws IOException {
        File file = fileOf(stagingRoot);
        if (paths == null || paths.isEmpty()) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        Files.write(file.toPath(), paths, StandardCharsets.UTF_8);
    }
}

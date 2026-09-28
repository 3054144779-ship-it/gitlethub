package org.openapitools.model;

import lombok.Data;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 远程模块的 Commit 对象
 * 结构与 gitlet_core 的 Commit 一致，用于反序列化存储的 commit 文件
 */
@Data
public class RemoteCommit implements Serializable {
    private static final long serialVersionUID = 1L;

    private String message;
    private String time;
    private String parent;
    private String secondParent;
    private List<String> mergeFiles;
    private Map<String, String> trackedFiles;

    public RemoteCommit() {}

    public RemoteCommit(String message, List<String> mergeFiles, String parent,
                        String secondParent, Map<String, String> trackedFiles) {
        this.message = message;
        this.time = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US).format(new Date());
        this.mergeFiles = mergeFiles;
        this.parent = parent;
        this.secondParent = secondParent;
        this.trackedFiles = trackedFiles;
    }
}

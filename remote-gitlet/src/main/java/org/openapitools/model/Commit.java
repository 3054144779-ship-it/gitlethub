package org.openapitools.model;


import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 表示一个gitlet提交对象。
 * 在高水平上确实如此。
 */
@Data
public class Commit implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 这个承诺的信息。 */
    private String message;
    /** 提交时间。 */
    private String time;
    /** 父提交。 */
    private String parent;
    /** 父提交(分支) */
    private String secondParent;
    /** 合并的文件 */
    private List<String> mergeFiles;
    /** .gitlet仓库中的文件集合的映射。 */
    private Map<String, String> trackedFiles;

    public Commit(String message, List<String> mergeFiles, String parent,
                  String secondParent, Map<String, String> trackedFiles) {
        this.message = message;
        this.time = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US).format(new Date());
        this.mergeFiles = mergeFiles;
        this.parent = parent;
        this.secondParent = secondParent;
        this.trackedFiles = trackedFiles;
    }
}

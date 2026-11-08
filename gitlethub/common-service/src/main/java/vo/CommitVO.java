package vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提交对象的 API 视图。
 * 与磁盘上的 {@code gitlet.commit.Commit}（Java 序列化的数据对象，SHA 来源）含义不同，不可合并。
 */
@Data
public class CommitVO {

    private String sha;

    private String message;

    /** 提交时间，格式为 "EEE MMM d HH:mm:ss yyyy Z"（沿用 CLI 的格式，非 ISO） */
    private String time;

    private String parent;

    private String secondParent;

    /** 提交者，取自 submission_record；多数历史提交没有记录，可能为 null */
    private Long authorId;

    private String authorName;

    private String branchName;

    /** 文件路径 -> blob Sha1；列表接口为 null，详情接口才有 */
    private Map<String, String> trackedFiles;

    private List<String> mergeFiles;
}

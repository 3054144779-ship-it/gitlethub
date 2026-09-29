package vo;

import lombok.Data;

/**
 * 提交 diff 中的单个文件变更。
 * changeType: ADDED / MODIFIED / DELETED
 */
@Data
public class FileChangeVO {

    private String path;

    private String changeType;

    private String oldSha;

    private String newSha;

    private String oldContent;

    private String newContent;
}

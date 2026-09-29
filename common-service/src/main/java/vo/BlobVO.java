package vo;

import lombok.Data;

/**
 * 单个文件内容。isBinary 为 true 时 content 为空字符串（二进制不按文本返回）。
 */
@Data
public class BlobVO {

    private String path;

    private String blobSha;

    private String content;

    private Boolean isBinary;
}

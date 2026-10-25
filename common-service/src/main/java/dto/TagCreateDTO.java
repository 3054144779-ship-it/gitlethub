package dto;

import lombok.Data;

/**
 * 创建标签
 */
@Data
public class TagCreateDTO {
    private String tagName;
    private String commitSha;
    private String description;
}

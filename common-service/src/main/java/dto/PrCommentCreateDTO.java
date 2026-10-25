package dto;

import lombok.Data;

/**
 * {
 *   "body": "这段需要判空，否则会 NPE",
 *   "commentType": 2,
 *   "filePath": "src/OrderService.java",
 *   "lineNumber": 42,
 *   "diffSide": 1,
 *   "commitSha": "a1b2c3...",
 *   "replyToId": null
 * }
 */
@Data
public class PrCommentCreateDTO {
    private String body;
    private Integer commentType;
    private String filePath;
    private Integer lineNumber;
    private Integer diffSide;
    private String commitSha;
    private Long replyToId;
}

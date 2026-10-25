package dto;

import lombok.Data;

/**
 * 发起 AI 审查：pullRequestId 与 commitSha 二选一
 */
@Data
public class AiReviewCreateDTO {
    private Long pullRequestId;
    private String commitSha;
}

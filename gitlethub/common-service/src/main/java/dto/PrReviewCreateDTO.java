package dto;

import lombok.Data;

/**
 * 提交 PR 评审：state 0=仅评论 / 1=通过 / 2=请求修改
 */
@Data
public class PrReviewCreateDTO {
    private Integer state;
    private String body;
    private String commitSha;
}

package vo;

import enums.AiReviewStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiReviewRecordVO {
    private Long id;
    private Long pullRequestId;
    private String commitSha;
    private String diffSha256;
    private String model;
    private AiReviewStatusEnum status;
    private String conclusion;
    private String errorMessage;
    private Integer durationMs;
    private Long triggerUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

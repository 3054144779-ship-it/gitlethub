package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import enums.AiReviewStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 审查记录：一次审查一条，diff_sha256 可用于去重 / 复用上次结论
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_review_record")
public class AiReviewRecord {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("pull_request_id")
    private Long pullRequestId;

    @TableField("commit_sha")
    private String commitSha;

    @TableField("diff_sha256")
    private String diffSha256;

    @TableField("model")
    private String model;

    @TableField("status")
    private AiReviewStatusEnum status;

    @TableField("conclusion")
    private String conclusion;

    @TableField("error_message")
    private String errorMessage;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("trigger_user_id")
    private Long triggerUserId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;
}

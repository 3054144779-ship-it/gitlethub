package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import enums.PrReviewStateEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PR 评审记录：同一评审人可多次提交，合并门禁取每人最新一条
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("pr_review")
public class PrReview {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("pull_request_id")
    private Long pullRequestId;

    @TableField("reviewer_id")
    private Long reviewerId;

    @TableField("state")
    private PrReviewStateEnum state;

    @TableField("body")
    private String body;

    @TableField("commit_sha")
    private String commitSha;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;
}

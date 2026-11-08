package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import enums.PrCommentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PR 评论：整体评论与行级评论共用一张表
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("pr_comment")
public class PrComment {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("pull_request_id")
    private Long pullRequestId;

    @TableField("user_id")
    private Long userId;

    @TableField("body")
    private String body;

    @TableField("comment_type")
    private PrCommentTypeEnum commentType;

    @TableField("file_path")
    private String filePath;

    @TableField("line_number")
    private Integer lineNumber;

    // 0=旧行(LEFT), 1=新行(RIGHT)
    @TableField("diff_side")
    private Integer diffSide;

    @TableField("commit_sha")
    private String commitSha;

    @TableField("reply_to_id")
    private Long replyToId;

    @TableField("review_id")
    private Long reviewId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;
}

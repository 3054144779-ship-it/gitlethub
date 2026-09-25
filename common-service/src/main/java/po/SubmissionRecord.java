package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SubmissionRecord 表对应的实体类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("submission_record")
public class SubmissionRecord {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("commit_sha")
    private String commitSha;

    @TableField("user_id")
    private Long userId;

    @TableField("branch_name")
    private String branchName;

    @TableField("submit_time")
    private LocalDateTime submitTime;

    @TableField("is_deleted")
    @TableLogic // MP的 BaseMapper 自动处理逻辑删除
    private Integer isDeleted;
}

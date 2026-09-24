package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * BranchPointer 表对应的实体类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("branch_pointer")
public class BranchPointer {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("branch_name")
    private String branchName;

    @TableField("commit_sha")
    private String commitSha;

    @TableField("is_default")
    private Integer isDefault;

    @TableField("is_protected")
    private Integer isProtected;

    @TableField("version")
    @Version
    private Integer version;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;

    @TableField("is_force_push")
    private Integer isForcePush;
}
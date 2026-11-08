package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import enums.CollaboratorLevelEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Collaborator 表对应的实体类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("collaborator")
public class Collaborator {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("user_id")
    private Long userId;

    @TableField("level")
    private CollaboratorLevelEnum level;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("auto_create_pr")
    private Integer autoCreatePr;  // 1=开启自动PR, 0=关闭（用户自己控制）

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;
}
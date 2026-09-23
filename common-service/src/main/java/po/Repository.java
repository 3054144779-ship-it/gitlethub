package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Repository 表对应的实体类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("repository")
public class Repository {
    @TableId
    private Long id;

    @TableField("owner_id")
    private Long ownerId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("visibility")
    private Integer visibility;

    @TableField("default_branch")
    private String defaultBranch;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;
}
package org.openapitools.model.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pull_request")
public class PullRequest {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("source_branch")
    private String sourceBranch;

    @TableField("target_branch")
    private String targetBranch;

    @TableField("status")
    private Integer status;

    @TableField("author_id")
    private Long authorId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;
}

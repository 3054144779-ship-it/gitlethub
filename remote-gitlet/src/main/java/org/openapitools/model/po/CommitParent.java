package org.openapitools.model.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * CommitParent 表对应的实体类
 */
@Data
@TableName("commit_parent")
public class CommitParent {
    @TableId
    private Long id;

    @TableField("repository_id")
    private Long repositoryId;

    @TableField("commit_sha")
    private String commitSha;

    @TableField("parent_sha")
    private String parentSha;
}

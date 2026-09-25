package po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CommitParent 表对应的实体类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
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

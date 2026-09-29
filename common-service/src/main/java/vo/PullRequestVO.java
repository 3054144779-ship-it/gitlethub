package vo;

import com.baomidou.mybatisplus.annotation.TableField;
import enums.PRStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PullRequestVO {
    private Long repositoryId;

    private String title;

    private String description;

    private String sourceBranch;

    private String targetBranch;

    private PRStatusEnum status;

    private Long authorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

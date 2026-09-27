package dto;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PullRequestDTO {
    // PR的标题
    private String title;
    // PR的描述
    private String description;
    // 源分支
    private String sourceBranch;
    // 目标分支
    private String targetBranch;
    // 作者ID
    private Long authorId;
}

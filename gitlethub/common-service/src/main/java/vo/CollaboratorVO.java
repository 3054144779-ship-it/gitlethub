package vo;

import enums.CollaboratorLevelEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协作者视图。Collaborator PO 里只有 userId，展示需要用户名，故这里带上 username。
 */
@Data
public class CollaboratorVO {

    private Long userId;

    private String username;

    private CollaboratorLevelEnum level;

    private Integer autoCreatePr;

    private LocalDateTime createTime;
}

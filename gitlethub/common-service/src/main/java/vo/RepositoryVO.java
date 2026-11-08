package vo;


import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RepositoryVO {
    private Long id;

    private Long ownerId;

    private String ownerName;

    private String name;

    private String description;

    private Integer visibility;

    private String defaultBranch;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer allowAutoPr;  // 1=允许自动PR, 0=不允许（owner 控制）
}

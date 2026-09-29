package dto;

import lombok.Data;

/**
 * 添加协作者。level 用 CollaboratorLevelEnum 的 code：2=所有者, 1=贡献者, 0=访客。
 */
@Data
public class CollaboratorCreateDTO {

    private String username;

    private Integer level;
}

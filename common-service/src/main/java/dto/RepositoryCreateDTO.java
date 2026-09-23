package dto;

import lombok.Data;

@Data
public class RepositoryCreateDTO {
    // 仓库所有者
    private String owner;
    // 仓库名称
    private String repo;
    // 仓库描述
    private String description;
    // 仓库可见性
    private Integer visibility;
}

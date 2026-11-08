package dto;

import lombok.Data;

@Data
public class UserSearchVO {
    // 用户ID（用户唯一UID）
    private Long userId;
    // 用户名
    private String username;
    // 账号创建时间
    private String createTime;
    // TODO：头像待实现
    private String avatar;
}

package dto;

import lombok.Data;

/**
 * 用户登录DTO
 */
@Data
public class UserLoginDTO {
    private String username;
    private String password;
    // 是否记住我，用于生成持久化cookie
    private String rememberMe;
}

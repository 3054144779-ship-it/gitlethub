package dto;

import lombok.Data;

/**
 * 个人中心修改资料：邮箱始终按传入值更新；密码仅在 newPassword 非空时修改。
 */
@Data
public class UserUpdateDTO {
    private String email;

    /** 修改密码时必须提供 */
    private String oldPassword;

    /** 为空表示保持原密码不变 */
    private String newPassword;
}

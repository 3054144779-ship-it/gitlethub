package vo;

import lombok.Builder;
import lombok.Data;

/**
 * 用户登录返回信息
 */
@Data
@Builder
public class UserLoginVO {
    private String token;
    private Long userId;
    private String username;
}

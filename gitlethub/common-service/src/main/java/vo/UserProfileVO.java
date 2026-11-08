package vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户资料视图，刻意不包含 password 字段。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserProfileVO {
    private Long id;
    private String username;
    private String email;
    private String avatar;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

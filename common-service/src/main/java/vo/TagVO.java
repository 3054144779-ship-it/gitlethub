package vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TagVO {
    private Long id;
    private String tagName;
    private String commitSha;
    private Long userId;
    private String userName;
    private String description;
    private LocalDateTime createTime;
}

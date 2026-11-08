package vo;

import enums.PrCommentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PrCommentVO {
    private Long id;
    private Long userId;
    private String userName;
    private String avatarUrl;
    private String body;
    private PrCommentTypeEnum commentType;
    private String filePath;
    private Integer lineNumber;
    private Integer diffSide;
    private String commitSha;
    private Long replyToId;
    private Long reviewId;
    private LocalDateTime createTime;
    /** 子回复；前端直接按树渲染，不用自己按 replyToId 分组 */
    private List<PrCommentVO> replies;
}

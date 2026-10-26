package vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PR 中单个文件的差异，供前端渲染行级评论。
 * changeType: ADDED / MODIFIED / DELETED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrDiffFileVO {

    private String path;

    private String changeType;

    private String oldSha;

    private String newSha;

    private boolean binary;

    /** 差异过大时只返回前若干行，前端据此提示 */
    private boolean truncated;

    private List<PrDiffLineVO> lines;
}

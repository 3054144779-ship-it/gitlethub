package vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR diff 中的一行：type=context/add/del。
 * 新增行只有 newLine，删除行只有 oldLine，上下文行两者都有。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrDiffLineVO {

    private String type;

    private Integer oldLine;

    private Integer newLine;

    private String content;
}

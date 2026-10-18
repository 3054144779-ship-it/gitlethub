package dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 差异数据
 *    "diff_data": [               // 前端传过来的结构化 Diff
 *      {
 *        "file_path": "OrderService.java",
 *        "old_content": "...",
 *        "new_content": "...",
 *        "diff": "..."
 *      }
 *    ],
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffData {
    private String file_path;
    private String old_content;
    private String new_content;
    private String diff;
}

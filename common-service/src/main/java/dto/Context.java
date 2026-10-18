package dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Context
 *     "context": {
 *       "pr_id": 999,                // 如果是 PR 场景
 *       "diff_data": [               // 前端传过来的结构化 Diff
 *         {
 *           "file_path": "OrderService.java",
 *           "old_content": "...",
 *           "new_content": "...",
 *           "diff": "..."
 *         }
 *       ],
 *     }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Context {
    private Long pr_id;
    private List<DiffData> diff_data;
}

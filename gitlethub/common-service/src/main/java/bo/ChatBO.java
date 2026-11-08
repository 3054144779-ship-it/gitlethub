package bo;

import dto.Context;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ai服务数据传输契约
 *     {
 *       "query": "解释一下这段改动",
 *       "owner": 1,
 *       "repo": 100,
 *       "context": {
 *         "pr_id": 999,                // 如果是 PR 场景
 *         "diff_data": [               // 前端传过来的结构化 Diff
 *           {
 *             "file_path": "OrderService.java",
 *             "old_content": "...",
 *             "new_content": "...",
 *             "diff": "..."
 *           }
 *         ],
 *       }
 *     }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatBO {
    private String session_id;
    private String query;
    private Long owner;
    private Long repo;
    private Context context;
}

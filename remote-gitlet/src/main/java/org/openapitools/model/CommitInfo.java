package org.openapitools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 *    "commits": [
 *        {
 *            "commit_sha": "a1b2c3",
 *            "message": "feat: 新增支付重试方法",
 *            "time": "2026-10-14T10:00:00Z",
 *            "merge_files": [file1Name, file2Name],
 *            "tracked_files": [
 *                {
 *                    "file_path": "src/OrderService.java",
 *                    "status": "MODIFIED"
 *                },
 *                {
 *                    "file_path": "src/PaymentUtil.java",
 *                    "status": "ADDED"
 *                }
 *            ]
 *        },
 *        {
 *            "commit_sha": "d4e5f6",
 *            "message": "fix: 修复并发竞态",
 *            "time": "2026-10-14T11:00:00Z",
 *            "merge_files": [],
 *            "tracked_files": [
 *                {
 *                    "file_path": "src/OrderService.java",
 *                    "status": "MODIFIED"
 *                }
 *            ]
 *        }
 *    ]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommitInfo {
    private String commitSha;
    private String message;
    private String time;
    private List<String> mergeFiles;
    private List<TrackedFile> trackedFiles;
}

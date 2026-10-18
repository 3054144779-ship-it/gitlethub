package org.openapitools;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class testSimpleMQ {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    public void testSend() {
        String path = "D:\\Job_project\\gitlet_ai_helper\\app\\main.py";
        Map<String, Object> mp = Map.of(
                "user_id", 123,   // 用户id
                "repository_id", 312, // 仓库id
                "file_path", path,    // 合并的文件路径
                "commit_sha", "test_sha",   // 提交的SHA1值
                "branch_name", "test_branch",  // 合并的分支名字
                "author", "hua_huo",       // PR 作者
                "commit_time", "test_time"       // 提交时间
        );

        rabbitTemplate.convertAndSend("gitlet.pr.exchange", "pr.merged", mp);
    }

    @Test
    public void testSend2() {
        String path = "D:\\Job_project\\gitlet_ai_helper\\tests\\test.md";
        Map<String, Object> mp = Map.of(
                "user_id", 123,   // 用户id
                "repository_id", 312, // 仓库id
                "file_path", path,    // 合并的文件路径
                "commit_sha", "test_sha",   // 提交的SHA1值
                "branch_name", "test_branch",  // 合并的分支名字
                "author", "hua_huo",       // PR 作者
                "commit_time", "test_time"       // 提交时间
        );

        rabbitTemplate.convertAndSend("gitlet.pr.exchange", "pr.merged", mp);
    }

    @Test
    public void testSend3() {
        String path = "D:\\Job_project\\gitlet_ai_helper\\tests\\数据处理.docx";
        Map<String, Object> mp = Map.of(
                "user_id", 123,   // 用户id
                "repository_id", 312, // 仓库id
                "file_path", path,    // 合并的文件路径
                "commit_sha", "test_sha",   // 提交的SHA1值
                "branch_name", "test_branch",  // 合并的分支名字
                "author", "hua_huo",       // PR 作者
                "commit_time", "test_time"       // 提交时间
        );

        rabbitTemplate.convertAndSend("gitlet.pr.exchange", "pr.merged", mp);
    }

    @Test
    public void testSend4() {
        String path = "D:\\Job_project\\gitlet_ai_helper\\tests\\项目记录.pdf";
        Map<String, Object> mp = Map.of(
                "user_id", 123,   // 用户id
                "repository_id", 312, // 仓库id
                "file_path", path,    // 合并的文件路径
                "commit_sha", "test_sha",   // 提交的SHA1值
                "branch_name", "test_branch",  // 合并的分支名字
                "author", "hua_huo",       // PR 作者
                "commit_time", "test_time"       // 提交时间
        );

        rabbitTemplate.convertAndSend("gitlet.pr.exchange", "pr.merged", mp);
    }

    @Test
    public void testSend5() {
        Map<String, Object> mp = Map.of(
                "user_id", 10001,
                "repository_id", 20005,
                "branch_name", "main",
                "pr_info", Map.of(
                        "pr_name", "增加支付超时重试",
                        "author", "zhangsan",
                        "commits", List.of(
                                Map.of(
                                        "commit_sha", "a1b2c3",
                                          "message", "feat: 新增支付重试方法",
                                        "time", "2026-10-14T10:00:00Z",
                                        "merge_files", List.of("file1Name", "file2Name"),
                                        "tracked_files", List.of(
                                                Map.of("file_path", "D:\\Job_project\\gitlethub\\common-service\\src\\main\\java\\jwt\\KeyPairManager.java", "status", "MODIFIED"),
                                                Map.of("file_path", "D:\\Job_project\\gitlethub\\common-service\\src\\main\\java\\dto\\CollaboratorCreateDTO.java", "status", "ADDED")
                                        )
                                ),
                                Map.of(
                                        "commit_sha", "d4e5f6",
                                        "message", "fix: 修复并发竞态",
                                        "time", "2026-10-14T11:00:00Z",
                                        "merge_files", List.of(),
                                        "tracked_files", List.of(
                                                Map.of("file_path", "D:\\Job_project\\gitlethub\\common-service\\src\\main\\java\\dto\\UserDTO.java", "status", "MODIFIED")
                                        )
                                )
                        )
                )
        );

        rabbitTemplate.convertAndSend("gitlet.pr.exchange", "pr.merged", mp);
    }

}

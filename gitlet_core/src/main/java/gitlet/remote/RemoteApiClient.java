package gitlet.remote;

import lombok.extern.slf4j.Slf4j;
import gitlet.utils.Utils;
import gitlet.commit.Commit;
import gitlet.remote.result.PushResult;
import gitlet.remote.result.PullResult;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.List;

/**
 * 远程 API 客户端：纯 POJO，不依赖 Spring。
 * 用 JDK 内置的 HttpClient 调用 remote-gitlet 的 REST API。
 * CLI 启动时手动 new 出来，传给需要的 Handler。
 */
@Slf4j
public class RemoteApiClient {

    private final HttpClient httpClient;
    private final String baseUrl;

    public RemoteApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)) // 连接超时10s
                .build();
    }

    /**
     * 推送对象（blobs + commits）到远程。
     * POST /api/remote/{owner}/{repo}/objects
     */
    public PushResult pushObjects(String owner, String repo, List<Commit> commits) {
        // 构造JSON：把每个commit的SHA + 内容（base64）打包
        String json = buildObjectUploadJson(commits);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/remote/" + owner + "/" + repo + "/objects"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePushResult(response);
        } catch (Exception e) {
            log.info("推送失败: {}", e.getMessage());
            return new PushResult(false, "推送失败: " + e.getMessage());
        }
    }


    /**
     * 更新远程分支指向。
     * POST /api/remote/{owner}/{repo}/refs/heads/{branch}
     * @param expectedOldSha：本地认为远程现在指向哪
     */
    public PushResult updateBranch(String owner, String repo, String branch,
                                   String newSha, String expectedOldSha) {
        // 构造 JSON：{ "sha": "xxx", "expectedOldSha": "yyy" }
        String json = String.format(
                "{\"sha\":\"%s\",\"expectedOldSha\":\"%s\"}",
                newSha,
                expectedOldSha != null ? expectedOldSha : ""
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/remote/" + owner + "/" + repo + "/refs/heads/" + branch))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePushResult(response);
        } catch (Exception e) {
            return new PushResult(false, "网络错误: " + e.getMessage());
        }
    }

    /**
     * 从远程拉取对象（全量或增量）。
     * GET /api/remote/{owner}/{repo}/pull?since={commitSha}
     */
    public PullResult pullObjects(String owner, String repo, String sinceCommitSha) {
        String url = baseUrl + "/api/remote/" + owner + "/" + repo + "/pull";
        if (sinceCommitSha != null && !sinceCommitSha.isEmpty()) {
            url += "?since=" + sinceCommitSha;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePullResult(response);
        } catch (Exception e) {
            log.info("拉取失败: {}", e.getMessage());
            return new PullResult(false, "网络错误: " + e.getMessage(), null);
        }
    }

    /**
     * 构建对象上传的 JSON
     * @param commits 要上传的提交列表
     * @return JSON 字符串
     */
    private String buildObjectUploadJson(List<Commit> commits) {
        // 简化版：把每个 commit 的 SHA + 序列化后的字节（base64）打包成 JSON
        // 实际实现需要把 Commit 序列化成 byte[]（参考 Dumpable 接口）
        StringBuilder sb = new StringBuilder();
        sb.append("{\"objects\":[");
        for (int i = 0; i < commits.size(); i++) {
            Commit c = commits.get(i);
            byte[] bytes = Utils.serialize(c);
            String sha = Utils.sha1(bytes);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            sb.append(String.format(
                    "{\"sha1\":\"%s\",\"type\":\"commit\",\"content\":\"%s\"}",
                    sha, base64
            ));
            if (i < commits.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 解析远程返回的推送结果
     * @param response 远程返回的响应
     * @return 推送结果
     */
    private PushResult parsePushResult(HttpResponse<String> response) {
        // 解析远程返回的 JSON：{"code":200,"message":"ok"} 或 {"code":50001,"message":"冲突"}
        // 简化版：只看 HTTP 状态码
        if (response.statusCode() == 200) {
            return new PushResult(true, "推送成功");
        } else {
            return new PushResult(false, "推送失败: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    /**
     * 解析远程返回的拉取结果
     * @param response 远程返回的响应
     * @return 拉取结果
     */
    private PullResult parsePullResult(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return new PullResult(false, "拉取失败: HTTP " + response.statusCode(), null);
        }
        // TODO: 解析远程返回的 ObjectBundle（blob/commit 列表）
        // 这里需要根据远程返回的 JSON 结构反序列化出 Commit 对象
        return new PullResult(true, "拉取成功", List.of()); // 占位
    }

}

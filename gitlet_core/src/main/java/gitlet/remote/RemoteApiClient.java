package gitlet.remote;

import gitlet.bo.GitletObject;
import gitlet.bo.ObjectBundle;
import gitlet.remote.result.PullResult;
import gitlet.remote.result.PushResult;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * POST /api/repos/{owner}/{repo}/objects
     */
    public PushResult pushObjects(String owner, String repo, ObjectBundle objects, String token) {
        // 构造JSON：把每个commit的SHA + 内容（base64）打包\
        /*
        {
            "objects": [
                {
                    "sha1": "xxx",
                    "type": "commit",
                    "content": "yyy"
                },
                ...
            ]
        }
         */

        // 使用 Jackson 库将 ObjectBundle 转换为 JSON 字符串
        String json = buildObjectUploadJson(objects);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/repos/" + owner + "/" + repo + "/objects"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        log.info("推送对象: {}", objects.toString());

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePushResult(response);
        } catch (Exception e) {
            log.error("推送失败", e);
            return new PushResult(false, "推送失败: " + e.getMessage());
        }
    }


    /**
     * 更新远程分支指向。
     * POST /api/repos/{owner}/{repo}/refs/heads/{branch}
     * @param expectedOldSha：本地认为远程现在指向哪
     */
    public PushResult updateBranch(String owner, String repo, String branch,
                                   String newSha, String expectedOldSha, String token) {
        // 构造 JSON：{ "sha": "xxx", "expectedOldSha": "yyy" }
        // 有 tracking 才带 expectedOldSha（做非快进校验）；首次推送省略该字段，让服务端放行。
        // 不然如果, expectedOldSha 为 null 会被非快进校验进行校验, 导致找不到null这个提交。

        String json = expectedOldSha != null
                ? String.format("{\"sha\":\"%s\",\"expectedOldSha\":\"%s\"}", newSha, expectedOldSha)
                : String.format("{\"sha\":\"%s\"}", newSha);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/repos/" + owner + "/" + repo + "/refs/" + branch))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePushResult(response);
        } catch (Exception e) {
            return new PushResult(false, "网络错误: " + e);
        }
    }

    /**
     * 从远程拉取对象（全量或增量）。
     * GET /api/repos/{owner}/{repo}/pull?since={commitSha}
     */
    public PullResult pullObjects(String owner, String repo, String sinceCommitSha, String token) {
        String url = baseUrl + "/api/repos/" + owner + "/" + repo + "/objects";
        if (sinceCommitSha != null && !sinceCommitSha.isEmpty()) {
            url += "?since=" + sinceCommitSha;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("pull 原始响应: status={}, body={}", response.statusCode(), response.body());
            return parsePullResult(response);
        } catch (Exception e) {
            log.info("拉取失败: {}", e);
            return new PullResult(false, "网络错误: " + e, null);
        }
    }

    /**
     * 读取远程分支指向的 SHA 值。
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名
     * @param branch 远程分支名
     * @return 远程分支指向的 SHA 值，如果读取失败则返回 null
     */
    public String getBranchSha(String owner, String repo, String branch, String token) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/repos/" + owner + "/" + repo + "/refs/" + branch))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                /*
                解析
                {
                    "code": 200,
                    "data": {
                        "branch": "xxx",
                        "commitSha": "abc123..."
                    }
                }
                 */
                String body = response.body();
                // 提取 commitSha 返回
                return parseBranchSha(body);
            }

            return null;
        } catch (Exception e) {
//            throw new RuntimeException("读取远程分支 SHA 值失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 登录远程仓库
     * @return 登录令牌
     */
    public String login(String username, String password) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/user/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.format("{\"username\":\"%s\",\"password\":\"%s\",\"rememberMe\":\"true\"}", username, new String(password))))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            Integer code = extractJsonInt(body, "code");
            if (response.statusCode() == 200 && code != null && code == 0) {
                log.info("登录成功");
                // 注意：data 是对象，token 在 data.token，取 "data" 会拿到 null
                return extractJsonString(body, "token");
            }
            log.warn("登录失败: {}", code);
        } catch (Exception e) {
            log.error("登录请求异常", e);
        }
        return null;
    }


    /**
     * 构建对象上传的 JSON（对象中不存在转义，选择手写 JSON 字符串，保证轻量纯库）
     * @param objects 要上传的对象列表
     * @return JSON 字符串
     */
    private String buildObjectUploadJson(ObjectBundle objects) {
        // 简化版：把每个 commit 的 SHA + 序列化后的字节（base64）打包成 JSON
        // 实际实现需要把 Commit 序列化成 byte[]（参考 Dumpable 接口）
        List<@Valid GitletObject> commits = objects.getObjects();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"objects\":[");
        for (int i = 0; i < commits.size(); i++) {
            GitletObject gitletObject = commits.get(i);
            // 确保 gitletObject 的内容是 byte[] 类型
            byte[] bytes = gitletObject.getContent();
            String sha = gitletObject.getSha1();
            if (bytes == null) {
                log.warn("对象 {} 的内容为 null，跳过", sha);
                continue;
            }
            String base64 = Base64.getEncoder().encodeToString(bytes);
            GitletObject.TypeEnum type = gitletObject.getType();
            sb.append(String.format(
                    "{\"sha1\":\"%s\",\"type\":\"%s\",\"content\":\"%s\"}",
                    sha, type.getValue(), base64
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
        if (response.statusCode() != 200) {
            return new PushResult(false,
                    "推送失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        String body= response.body();
        Integer code = extractJsonInt(body, "code");
        if (code != null && code == 0) {
            return new PushResult(true, "推送成功");
        }

        return new PushResult(false, "推送失败: code=" + code + ", " +
                "message=" + extractJsonString(body, "message"));
    }

    /**
     * 从 JSON 中提取状态码
     * @param json JSON 字符串
     * @param key JSON 中的键
     * @return 提取的整数值，如果未找到则返回 null
     */
    private Integer extractJsonInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
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

        String body = response.body();

        // 提取 objects 数组内容
        String objectsArrayContent = extractJsonArray(body, "objects");
        if (objectsArrayContent == null) {
            return new PullResult(true, "远程仓库为空", List.of());
        }

        // 逐个提取 object：{ "sha1":"...", "type":"...", "content":"..." }
        List<GitletObject> gitletObjects = new ArrayList<>();
        Pattern pattern = Pattern.compile(
                "\"sha1\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"content\"\\s*:\\s*\"([^\"]+)\""
        );
        Matcher matcher = pattern.matcher(objectsArrayContent);

        while (matcher.find()) {
            String sha1 = matcher.group(1);
            String type = matcher.group(2);
            byte[] content = Base64.getDecoder().decode(matcher.group(3));

            GitletObject obj = new GitletObject()
                    .sha1(sha1)
                    .type("commit".equals(type) ? GitletObject.TypeEnum.COMMIT : GitletObject.TypeEnum.BLOB)
                    .content(content);
            gitletObjects.add(obj);
        }

        ObjectBundle bundle = new ObjectBundle().objects(gitletObjects);
        return new PullResult(true, "拉取成功，获取 " + gitletObjects.size() + " 个对象", List.of(bundle));
    }

    private String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]+)\""
        );
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private String extractJsonArray(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx == -1) return null;
        int bracketStart = json.indexOf("[", keyIdx);
        if (bracketStart == -1) return null;

        int depth = 0;
        for (int i = bracketStart; i < json.length(); i++) {
            if (json.charAt(i) == '[') depth++;
            else if (json.charAt(i) == ']') {
                depth--;
                if (depth == 0) return json.substring(bracketStart, i + 1);
            }
        }
        return null;
    }

    /**
     * 解析远程返回的远程分支指向 JSON 结果
     * @param body 远程返回的 JSON 字符串
     * @return 远程分支指向的 SHA 值
     * {
     *     "code": 200,
     *     "data": {
     *         "branch": "xxx",
     *         "sha": "abc123..."
     *     }
     * }
     */
    private String parseBranchSha(String body) {
        Pattern p = Pattern.compile(
            "\"sha\"\\s*:\\s*\"([^\"]+)\""
        );
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 登出远程仓库：吊销服务端 token，返回是否成功。
     */
    public boolean logout(String token) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/user/logout"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Integer code = extractJsonInt(response.body(), "code");
            if (response.statusCode() == 200 && code != null && code == 0) {
                log.info("登出成功");
                return true;
            }
            log.warn("登出失败: code={}", code);
        } catch (Exception e) {
            log.error("登出请求异常", e);
        }
        return false;
    }
}

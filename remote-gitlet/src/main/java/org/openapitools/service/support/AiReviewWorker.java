package org.openapitools.service.support;

import bo.ChatBO;
import dto.Context;
import dto.DiffData;
import enums.AiReviewStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.mapper.RemoteAiReviewRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import po.AiReviewRecord;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 审查的异步执行器：单独成 bean，保证 @Async 走代理（同类自调用不会异步）。
 */
@Slf4j
@Component
public class AiReviewWorker {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_ERROR_LENGTH = 1000;

    @Autowired
    private RemoteAiReviewRecordMapper remoteAiReviewRecordMapper;

    @Autowired
    private WebClient webClient;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Async
    public void run(Long recordId, Long ownerId, Long repositoryId, Long prId, String prName,
                    String commitSha, List<DiffData> diffData) {
        long start = System.currentTimeMillis();
        try {
            Context context = Context.builder()
                    .pr_id(prId)
                    .pr_name(prName)
                    .diff_data(diffData)
                    .build();
            ChatBO chatBO = ChatBO.builder()
                    .session_id("ai-review-" + recordId)
                    .query(buildQuery(prId, commitSha))
                    .owner(ownerId)
                    .repo(repositoryId)
                    .context(context)
                    .build();

            StringBuilder conclusion = new StringBuilder();
            webClient.post()
                    .uri(aiServiceUrl + "/api/ai")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(chatBO)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new RuntimeException(
                                            "AI 服务返回错误 status=" + resp.statusCode().value() + " body=" + body))))
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .timeout(TIMEOUT)
                    .doOnNext(event -> {
                        if (event.data() != null && !event.data().isEmpty()) {
                            conclusion.append(event.data());
                        }
                    })
                    .blockLast();

            if (conclusion.length() == 0) {
                update(recordId, AiReviewStatusEnum.FAILED, null, "AI 服务未返回内容", start);
                return;
            }
            update(recordId, AiReviewStatusEnum.SUCCESS, conclusion.toString(), null, start);
        } catch (Exception e) {
            log.error("AI 审查失败 recordId={}, commitSha={}", recordId, commitSha, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (message.length() > MAX_ERROR_LENGTH) {
                message = message.substring(0, MAX_ERROR_LENGTH);
            }
            update(recordId, AiReviewStatusEnum.FAILED, null, message, start);
        }
    }

    private void update(Long recordId, AiReviewStatusEnum status, String conclusion,
                        String errorMessage, long start) {
        AiReviewRecord record = new AiReviewRecord();
        record.setId(recordId);
        record.setStatus(status);
        record.setConclusion(conclusion);
        record.setErrorMessage(errorMessage);
        record.setDurationMs((int) (System.currentTimeMillis() - start));
        record.setUpdateTime(LocalDateTime.now());
        remoteAiReviewRecordMapper.updateById(record);
    }

    private String buildQuery(Long prId, String commitSha) {
        if (prId != null) {
            return "请审查本次 Pull Request 的代码变更，按“问题 / 影响 / 建议”的结构输出结论，"
                    + "重点关注正确性、并发、安全与可维护性。";
        }
        return "请审查提交 " + commitSha + " 的代码变更，按“问题 / 影响 / 建议”的结构输出结论，"
                + "重点关注正确性、并发、安全与可维护性。";
    }
}

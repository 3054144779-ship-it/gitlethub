package org.openapitools.service.impl;

import bo.ChatBO;
import dto.ChatDTO;
import dto.Context;
import org.openapitools.handler.DiffBuilder;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import po.PullRequest;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
public class AIServiceImpl implements AIService {

    @Autowired
    private RemotePullRequestMapper remotePullRequestMapper;

    @Autowired
    private RemoteUserMapper remoteUserMapper;

    @Autowired
    private RemoteRepositoryMapper remoteRepositoryMapper;

    @Autowired
    private DiffBuilder diffBuilder;

    @Autowired
    private WebClient webClient;

    // 共享心跳线程池（daemon，避免每次请求 new 一个）
    private static final ScheduledExecutorService HEARTBEAT =
            Executors.newScheduledThreadPool(
                    4,
                    r -> {
                        Thread t = new Thread(r, "sse-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });

    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    @Override
    public SseEmitter chat(String owner, String repo, ChatDTO chatDTO) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Disposable> subRef = new AtomicReference<>();

        Runnable completeOnce = () -> {
            if (completed.compareAndSet(false, true)) emitter.complete();
        };
        Consumer<Throwable> errOnce = e -> {
            if (completed.compareAndSet(false, true)) emitter.completeWithError(e);
        };

        // 周期性心跳：每 15s 发一行 SSE 注释帧，防中间链路 idle 超时且尽早发现前端断开
        ScheduledFuture<?> hb = HEARTBEAT.scheduleAtFixedRate(() -> {
            if (completed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                // 发送失败多半是前端已断开，交给主流程收敛，这里忽略即可
                errOnce.accept(e);
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // 流结束时务必取消心跳，否则线程池会一直持有任务
        emitter.onCompletion(() -> { hb.cancel(true); disposeQuietly(subRef.get()); });
        emitter.onError(t -> { hb.cancel(true); disposeQuietly(subRef.get()); });
        emitter.onTimeout(() -> {
            hb.cancel(true);
            disposeQuietly(subRef.get());
            completeOnce.run();
        });

        Disposable sub = Mono.fromCallable(() -> buildChatBO(owner, repo, chatDTO))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(chatBO -> webClient.post()
                        .uri("/api/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(chatBO)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, resp ->
                                resp.bodyToMono(String.class).defaultIfEmpty("")
                                        .flatMap(b -> Mono.error(new RuntimeException(
                                                "AI 服务返回错误 status=" + resp.statusCode().value() + " body=" + b))))
                        .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {}))
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            String data = event.data();
                            if (data == null || data.isEmpty()) return;
                            try {
                                SseEmitter.SseEventBuilder builder = SseEmitter.event()
                                        .data(data, MediaType.TEXT_PLAIN);
                                // 上游未带 id/event 字段时为 null，需判空避免生成 "event:null" 坏帧
                                if (event.id() != null) builder.id(event.id());
                                if (event.event() != null) builder.name(event.event());
                                emitter.send(builder);
                            } catch (Exception e) {
                                disposeQuietly(subRef.get());
                                errOnce.accept(e);
                            }
                        },
                        errOnce,
                        completeOnce
                );

        subRef.set(sub);
        // 订阅可能在 set 之前就已出错/完成（onNext 里 subRef 还是 null），此处补 dispose 防泄漏
        if (completed.get()) {
            disposeQuietly(sub);
        }
        return emitter;   // 让 Controller 直接返回 emitter
    }

    private static void disposeQuietly(Disposable d) {
        if (d != null && !d.isDisposed()) d.dispose();
    }

    /**
     * 构建 ChatBO
     * @param owner 所有者
     * @param repo 仓库名
     * @param chatDTO 会话ID
     * @return ChatBO
     */
    private ChatBO buildChatBO(String owner, String repo, ChatDTO chatDTO) {
        Context context = null;
        Long ownerId = remoteUserMapper.getUserId(owner);
        Long repositoryId = ownerId == null ? null : remoteRepositoryMapper.getRepositoryId(ownerId, repo);

        // 仅 PR 审查场景组装 diff；其余场景 diff_data 为空，代码由调用方放进 query
        if (chatDTO.getPr_id() != null) {
            PullRequest pr = remotePullRequestMapper.selectById(chatDTO.getPr_id());
            if (pr != null && repositoryId != null) {
                log.info("PR diff 构建入参: repositoryId={}, sourceBranch={}, targetBranch={}",
                        repositoryId, pr.getSourceBranch(), pr.getTargetBranch());
                context = Context.builder()
                        .pr_id(chatDTO.getPr_id())
                        .pr_name(pr.getTitle())
                        .build();
                context.setDiff_data(
                        diffBuilder.buildPrDiff(repositoryId, pr.getSourceBranch(), pr.getTargetBranch()));
                log.info("PR diff 构建结果: diff_data size={}",
                        context.getDiff_data() == null ? "null" : context.getDiff_data().size());
            }
        }

        return ChatBO.builder()
                .session_id(chatDTO.getSession_id())
                .query(chatDTO.getQuery())
                .owner(ownerId)
                .repo(repositoryId)
                .context(context)
                .build();
    }

}

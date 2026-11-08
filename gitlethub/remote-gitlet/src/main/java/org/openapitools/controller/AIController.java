package org.openapitools.controller;

import dto.ChatDTO;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/ai/{owner}/{repo}")
public class AIController {
    @Autowired
    private AIService aiService;

    /**
     * 调用AI服务
     * @param chatDTO 聊天参数
     * @return 聊天结果
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String owner, @PathVariable String repo, @RequestBody ChatDTO chatDTO) {
        log.info("收到流式聊天请求 owner: {}, repo: {}, query: {}", owner, repo, chatDTO.getQuery());
        return aiService.chat(owner, repo, chatDTO);
    }

}

package org.openapitools.controller;

import common.Result;
import dto.ChatDTO;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    @PostMapping("/chat")
    public Result<String> chat(@PathVariable String owner, @PathVariable String repo, @RequestBody ChatDTO chatDTO) {
        log.info("收到来自的聊天请求 owner: {}, repo: {}, query: {}", owner, repo, chatDTO.getQuery());
        return Result.ok(aiService.chat(owner, repo, chatDTO));
    }

}

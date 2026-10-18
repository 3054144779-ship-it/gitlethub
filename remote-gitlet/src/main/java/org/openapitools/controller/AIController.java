package org.openapitools.controller;

import common.Result;
import dto.ChatDTO;
import org.openapitools.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import vo.ChatVO;

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
        return Result.ok(aiService.chat(owner, repo, chatDTO));
    }

}

package org.openapitools.service;

import common.Result;
import dto.ChatDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vo.ChatVO;

public interface AIService {
    /**
     * ai服务
     * @param owner 仓库所有人
     * @param repo 仓库编号
     * @param chatDTO 对话数据
     * @return 响应结果
     */
    SseEmitter chat(String owner, String repo, ChatDTO chatDTO);
}

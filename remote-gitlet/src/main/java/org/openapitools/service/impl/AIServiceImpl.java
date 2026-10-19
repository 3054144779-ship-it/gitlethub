package org.openapitools.service.impl;

import bo.ChatBO;
import common.Result;
import dto.ChatDTO;
import dto.Context;
import org.openapitools.handler.DiffBuilder;
import org.openapitools.mapper.RemotePullRequestMapper;
import org.openapitools.mapper.RemoteRepositoryMapper;
import org.openapitools.mapper.RemoteUserMapper;
import org.openapitools.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;
import po.PullRequest;
import vo.ChatVO;

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
    private RestTemplate restTemplate;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Override
    public String chat(String owner, String repo, ChatDTO chatDTO) {
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

        ChatBO chatBO = ChatBO.builder()
                .session_id(chatDTO.getSession_id())
                .query(chatDTO.getQuery())
                .owner(ownerId)
                .repo(repositoryId)
                .context(context)
                .build();

        return restTemplate.postForObject(
                aiServiceUrl + "/api/ai",
                chatBO,
                String.class
        );
    }
}

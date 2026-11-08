package org.openapitools.interceptor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.Result;
import common.ResultCode;
import enums.CollaboratorLevelEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.openapitools.filter.JwtAuthFilter;
import org.openapitools.mapper.RemoteCollaboratorMapper;
import org.openapitools.message.MsgConstant;
import org.openapitools.service.support.RepositoryObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import po.Collaborator;
import po.Repository;

import java.nio.charset.StandardCharsets;

/**
 * 读接口的可见性拦截：命中 {@code GET /api/repos/{owner}/{repo}[/...]} 时，
 * 私有仓库只有 OWNER / CONTRIBUTOR 能通过，其余一律按「仓库不存在」拒绝。
 *
 * <p>{@link JwtAuthFilter} 只注入身份不拦截，各 controller 的写接口在 service 层按等级鉴权；
 * 这里只补读接口这一层的可见性：不这么做的话，列表虽然过滤了，直接改地址栏访问详情
 * 及其子资源（branches/commits/tree/blob/PR/pull/clone）仍能拿到私有仓库数据。
 *
 * <p>拒绝用 404（复用 REPOSITORY_NOT_FOUND）而非 403，避免向无权限者暴露「这个私有仓库存在」。
 */
@Component
public class RepoVisibilityInterceptor implements HandlerInterceptor {

    @Autowired
    private RemoteCollaboratorMapper remoteCollaboratorMapper;
    @Autowired
    private RepositoryObjectReader repositoryObjectReader;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 写接口（push/建 PR 等）有各自的等级校验，这里只管读
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String[] seg = pathSegments(request);
        // 只拦 /api/repos/{owner}/{repo}[/...]；列表 /api/repos 与 /api/repos/{repositoryId} 交给各自 service
        if (seg.length < 4 || !"api".equals(seg[0]) || !"repos".equals(seg[1])) {
            return true;
        }
        // 用户不存在 / 仓库不存在 → 由 service 返回「仓库不存在」；
        // 解析结果已缓存，正常请求这里不再打用户表与仓库表
        Repository repository = repositoryObjectReader.resolveRepository(seg[2], seg[3]);
        if (repository == null) {
            return true;
        }
        Long userId = (Long) request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE);
        if (isVisibleTo(repository, userId)) {
            return true;
        }
        writeRepositoryNotFound(response);
        return false;
    }

    /** 公开仓库，或调用者是仓库 OWNER / CONTRIBUTOR */
    private boolean isVisibleTo(Repository repository, Long userId) {
        if (repository.getVisibility() != null && repository.getVisibility() == 1) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        if (userId.equals(repository.getOwnerId())) {
            return true;
        }
        return remoteCollaboratorMapper.selectCount(Wrappers.<Collaborator>lambdaQuery()
                .eq(Collaborator::getUserId, userId)
                .eq(Collaborator::getRepositoryId, repository.getId())
                .in(Collaborator::getLevel,
                        CollaboratorLevelEnum.OWNER.getCode(),
                        CollaboratorLevelEnum.CONTRIBUTOR.getCode())) > 0;
    }

    private void writeRepositoryNotFound(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ResultCode.REPOSITORY_NOT_FOUND, MsgConstant.THE_WAREHOUSE_DOES_NOT_EXIST)));
    }

    /** requestURI 去掉 contextPath 后按 / 切分；开头的斜杠会被清掉 */
    private String[] pathSegments(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        uri = uri.replaceAll("^/+", "");
        return uri.isEmpty() ? new String[0] : uri.split("/");
    }
}

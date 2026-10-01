package org.openapitools.controller;

import common.Result;
import dto.CollaboratorCreateDTO;
import dto.PageDTO;
import dto.RepositoryCreateDTO;
import org.openapitools.filter.JwtAuthFilter;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.service.RemoteService;
import org.openapitools.service.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vo.CollaboratorVO;
import vo.RepositoryVO;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepositoryController {

    @Autowired
    private RemoteService remoteService;
    @Autowired
    private RepositoryService repositoryService;

    /**
     * 创建当前用户的远程仓库
     * @param repositoryCreateDTO  (required) 创建仓库的DTO
     * @return 创建结果
     */
    @PostMapping
    public ResponseEntity<Result<ObjectUploadResult>> createRemoteRepository(
            @RequestBody RepositoryCreateDTO repositoryCreateDTO) {

        Result<ObjectUploadResult> response = remoteService.createRemoteRepository(repositoryCreateDTO);
        return ResponseEntity.ok(response);
    }

    /**
     * 更新远程仓库配置
     * @param repositoryCreateDTO  (required) 更新仓库的DTO
     * @return 更新结果
     */
    @PutMapping
    public ResponseEntity<Result<String>> updateRemoteRepository(
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId,
            @RequestBody RepositoryCreateDTO repositoryCreateDTO) {
        Result<String> response = repositoryService.updateRemoteRepository(userId, repositoryCreateDTO);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取远程仓库配置
     * @param repositoryId 远程仓库ID
     * @return 远程仓库配置
     */
    @GetMapping("/{repositoryId}")
    public ResponseEntity<Result<RepositoryVO>> getRemoteRepository(
            @PathVariable String repositoryId) {
        Result<RepositoryVO> response = repositoryService.getRemoteRepository(repositoryId);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取远程仓库配置
     * @param owner 远程仓库所有者
     * @param keyword 远程仓库关键字
     * @param pageDTO 分页参数
     *                page: 页码，size: 每页大小
     * @return 远程仓库列表
     */
    @GetMapping
    public ResponseEntity<Result<List<RepositoryVO>>> getAllRemoteRepository(@RequestParam("owner") String owner,
                                                               @RequestParam("keyword") String keyword,
                                                               PageDTO pageDTO) {
        Result<List<RepositoryVO>> response = repositoryService.getAllRemoteRepository(owner, keyword, pageDTO);
        return ResponseEntity.ok(response);
    }

    /**
     * 按 owner/repo 获取远程仓库配置（与上面的 /{repositoryId} 段数不同，无歧义）
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @return 远程仓库配置
     */
    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<Result<RepositoryVO>> getRepositoryByOwnerAndRepo(
            @PathVariable String owner, @PathVariable String repo) {
        return ResponseEntity.ok(repositoryService.getRepositoryByOwnerAndRepo(owner, repo));
    }

    /**
     * 列出仓库的协作者
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @return 协作者列表
     */
    @GetMapping("/{owner}/{repo}/collaborators")
    public ResponseEntity<Result<List<CollaboratorVO>>> listCollaborators(
            @PathVariable String owner, @PathVariable String repo) {
        return ResponseEntity.ok(repositoryService.listCollaborators(owner, repo));
    }

    /**
     * 添加协作者（仅 OWNER）
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @param collaboratorCreateDTO 待添加的协作者
     * @return 添加结果
     */
    @PostMapping("/{owner}/{repo}/collaborators")
    public ResponseEntity<Result<String>> addCollaborator(
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId,
            @PathVariable String owner, @PathVariable String repo,
            @RequestBody CollaboratorCreateDTO collaboratorCreateDTO) {
        return ResponseEntity.ok(repositoryService.addCollaborator(userId, owner, repo, collaboratorCreateDTO));
    }

    /**
     * 更新协作者
     * @param userId 协作者用户ID
      * @param autoCreatePr 自动创建 PR
     * @return 更新结果
     */
    @PutMapping("/{owner}/{repo}/collaborators")
    public ResponseEntity<Result<String>> updateCollaborator(
            @PathVariable String owner, @PathVariable String repo,
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long userId,
            @RequestParam("auto_create_pr") boolean autoCreatePr) {
        return ResponseEntity.ok(repositoryService.updateCollaborator(owner, repo, userId, autoCreatePr));
    }

    /**
     * 移除协作者（仅 OWNER）
     * @param owner 远程仓库所有者
     * @param repo 远程仓库名称
     * @param userId 被移除的协作者用户ID
     * @return 移除结果
     */
    @DeleteMapping("/{owner}/{repo}/collaborators/{userId}")
    public ResponseEntity<Result<String>> removeCollaborator(
            @RequestAttribute(value = JwtAuthFilter.USER_ID_ATTRIBUTE, required = false) Long operatorId,
            @PathVariable String owner, @PathVariable String repo,
            @PathVariable Long userId) {
        return ResponseEntity.ok(repositoryService.removeCollaborator(operatorId, owner, repo, userId));
    }
}

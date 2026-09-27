package org.openapitools.controller;

import common.Result;
import dto.RepositoryCreateDTO;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.service.RemoteService;
import org.openapitools.service.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestBody RepositoryCreateDTO repositoryCreateDTO) {
        Result<String> response = repositoryService.updateRemoteRepository(repositoryCreateDTO);
        return ResponseEntity.ok(response);
    }

}

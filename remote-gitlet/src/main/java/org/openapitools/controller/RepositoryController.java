package org.openapitools.controller;

import common.Result;
import dto.RepositoryCreateDTO;
import org.openapitools.model.ObjectUploadResult;
import org.openapitools.service.RemoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repos")
public class RepositoryController {

    @Autowired
    private RemoteService remoteService;

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
}

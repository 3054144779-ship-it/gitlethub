package com.huahuo.controller;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.huahuo.config.OSSConfig;
import com.huahuo.message.MsgConstant;
import common.Result;
import common.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/oss")
public class OSSController {
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    @Autowired
    private OSS ossClient;
    @Autowired
    private OSSConfig ossConfig;

    @GetMapping("/presigned-url")
    public Result<Map<String, String>> getPresignedUrl(@RequestParam String fileName,
                                                       @RequestParam String contentType) {
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(type)) {
            return Result.fail(ResultCode.BAD_REQUEST, MsgConstant.AVATAR_TYPE_INVALID);
        }

        // 生成唯一对象名，防止文件名冲突（如：avatar/2026/10/uuid_filename）
        String objectName = "avatar/" + LocalDate.now() + "/" + UUID.randomUUID() + "_" + fileName;

        // 设置URL过期时间，例如 15 分钟
        Date expiration = new Date(System.currentTimeMillis() + 15 * 60 * 1000);

        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                ossConfig.getBucketName(), objectName, HttpMethod.PUT);
        request.setExpiration(expiration);
        // Content-Type 参与签名，浏览器 PUT 时必须原样带上这个值
        request.setContentType(type);

        // 生成预签名 URL
        URL signedUrl = ossClient.generatePresignedUrl(request);

        // 返回给前端：上传地址 + 最终访问的完整 URL + 参与签名的 Content-Type
        String accessUrl = ossConfig.getUrlPrefix() + objectName;
        return Result.ok(Map.of(
                "uploadUrl", signedUrl.toString(),
                "accessUrl", accessUrl,
                "contentType", type));
    }
}
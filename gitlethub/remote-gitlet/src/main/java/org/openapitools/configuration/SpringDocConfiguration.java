package org.openapitools.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class SpringDocConfiguration {

    @Bean(name = "org.openapitools.configuration.SpringDocConfiguration.apiInfo")
    OpenAPI apiInfo() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("gitlethub 仓库服务 API")
                                .description("仓库与对象传输的接口契约。由 remote-gitlet 实现，经 gateway 暴露给 CLI。  这份文件是「接口」的唯一权威来源。改接口请改这里，不要改 Markdown。  约定： - 统一响应结构 `{code, message, data}`，见 ApiResponse。`code` 是业务码段（0 成功），   与 HTTP 状态码解耦；HTTP 状态码仍保留语义，给网关和通用中间件用。→ D18 - 鉴权、限流、日志过滤都在 gateway 完成，本服务假定到达的请求已通过校验。→ D9、D19 - gateway 自身的接口（`/health`、429 限流响应）不在这份契约里。 - AI 接口属于独立 project `gitlet_ai_helper`，契约跟着那个项目走，不在这里。→ D15  校验：npx @redocly/cli lint docs/openapi.yaml 生成骨架：openapi-generator generate -i docs/openapi.yaml -g spring -o remote-gitlet ")
                                .version("0.1.0")
                )
                .components(
                        new Components()
                                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                )
                )
        ;
    }
}
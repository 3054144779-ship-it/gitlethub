package org.openapitools.configuration;

import org.openapitools.interceptor.RepoVisibilityInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RepoVisibilityInterceptor repoVisibilityInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(repoVisibilityInterceptor).addPathPatterns("/api/repos/**");
    }
}

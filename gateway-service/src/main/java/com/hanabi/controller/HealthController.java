package com.hanabi.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.xml.transform.Result;

/**
 * 健康检查
 */
@Controller
@RequestMapping("/health")
public class HealthController {
    public String healthCheck() {
        return "healthy";
    }
}

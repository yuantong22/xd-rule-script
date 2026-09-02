package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查。用于确认后端起来了、前端代理通了。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @PostMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "app", "script-back"));
    }
}

package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 运行请求。params 的 key 是占位符名，value 一律是字符串，由后端按类型校验并转字面量 */
public record RunRequest(
        @NotNull(message = "不能为空") String scriptContent,
        Map<String, String> params) {}

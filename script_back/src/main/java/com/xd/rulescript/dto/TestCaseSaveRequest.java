package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 保存测试用例 */
public record TestCaseSaveRequest(
        @NotNull(message = "规则 ID 不能为空") Long ruleId,
        @NotBlank(message = "用例名称不能为空") String name,
        @NotNull(message = "参数不能为空") Map<String, String> params) {}

package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 更新规则。除 ruleId 外其余字段为 null 表示该项不改 */
public record RuleUpdateRequest(
        @NotNull(message = "规则 ID 不能为空") Long ruleId,
        String name,
        String description,
        String scriptContent) {}

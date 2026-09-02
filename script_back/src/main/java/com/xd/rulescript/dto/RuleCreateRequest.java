package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建规则 */
public record RuleCreateRequest(
        @NotBlank(message = "规则名称不能为空") String name,
        String description) {}

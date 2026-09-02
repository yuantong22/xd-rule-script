package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 查询对话历史 */
public record ChatHistoryRequest(@NotNull(message = "规则 ID 不能为空") Long ruleId) {}

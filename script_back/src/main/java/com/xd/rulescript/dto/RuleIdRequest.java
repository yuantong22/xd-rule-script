package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 只带规则 ID 的请求（详情、删除、对话历史、清空对话、用例列表共用） */
public record RuleIdRequest(@NotNull(message = "规则 ID 不能为空") Long ruleId) {}

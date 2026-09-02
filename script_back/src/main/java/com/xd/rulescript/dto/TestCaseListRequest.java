package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 查询测试用例列表 */
public record TestCaseListRequest(@NotNull(message = "规则 ID 不能为空") Long ruleId) {}

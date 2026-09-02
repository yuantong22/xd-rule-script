package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 删除测试用例 */
public record TestCaseIdRequest(@NotNull(message = "用例 ID 不能为空") Long testCaseId) {}

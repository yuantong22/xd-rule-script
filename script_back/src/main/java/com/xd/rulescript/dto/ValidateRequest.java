package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 校验请求。校验一律针对编辑器当前内容（含未保存修改），因此只传 scriptContent，不传 ruleId */
public record ValidateRequest(@NotNull(message = "不能为空") String scriptContent) {}

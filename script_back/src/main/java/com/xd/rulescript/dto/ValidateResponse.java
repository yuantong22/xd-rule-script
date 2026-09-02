package com.xd.rulescript.dto;

import java.util.List;

/** 校验响应：语法结果 + 占位符列表 + AI 审查 */
public record ValidateResponse(
        boolean syntaxOk,
        Integer errorLine,
        String errorMessage,
        List<PlaceholderInfo> placeholders,
        AiReviewResult aiReview) {}

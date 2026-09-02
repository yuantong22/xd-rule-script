package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 发送对话。scriptContent 是编辑器当前内容，作为上下文一并发给大模型 */
public record ChatSendRequest(
        @NotNull(message = "规则 ID 不能为空") Long ruleId,
        @NotBlank(message = "消息内容不能为空") String message,
        String scriptContent) {}

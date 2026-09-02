package com.xd.rulescript.dto;

/** 对话消息（回显用） */
public record ChatMessageDto(String role, String content, String createdAt) {}

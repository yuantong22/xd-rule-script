package com.xd.rulescript.dto;

/** 规则详情（含脚本内容） */
public record RuleDetailResponse(
        Long id,
        String name,
        String description,
        String scriptContent,
        String updatedAt) {}

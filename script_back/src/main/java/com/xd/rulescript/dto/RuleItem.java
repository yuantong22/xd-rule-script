package com.xd.rulescript.dto;

/** 列表行。updatedAt 已格式化为 yyyy-MM-dd HH:mm:ss 字符串，前端直接展示 */
public record RuleItem(Long id, String name, String description, String updatedAt) {}

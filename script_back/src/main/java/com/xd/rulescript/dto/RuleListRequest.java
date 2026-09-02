package com.xd.rulescript.dto;

/** 规则列表查询。name 为空表示不过滤；page 从 1 开始 */
public record RuleListRequest(String name, Integer page, Integer size) {}

package com.xd.rulescript.dto;

import java.util.List;

/** 规则分页结果 */
public record RulePageResponse(List<RuleItem> items, long total, int page, int size) {}

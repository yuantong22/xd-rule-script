package com.xd.rulescript.dto;

import java.util.Map;

/** 测试用例（返回给前端） */
public record TestCaseDto(Long id, String name, Map<String, String> params) {}

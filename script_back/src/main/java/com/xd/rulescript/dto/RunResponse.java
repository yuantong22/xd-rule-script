package com.xd.rulescript.dto;

/** 运行响应 */
public record RunResponse(boolean success, String value, String errorMessage, boolean timeout) {}

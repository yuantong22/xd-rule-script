package com.xd.rulescript.dto;

/** 沙箱运行结果。timeout=true 表示因超过 5 秒被中断 */
public record RunResult(boolean success, String value, String errorMessage, boolean timeout) {}

package com.xd.rulescript.dto;

/** 占位符及其推断类型。type 只会是 int / long / double / boolean / String 之一 */
public record PlaceholderInfo(String name, String type) {}

package com.xd.rulescript.dto;

/**
 * 大模型审查结果。
 * @param text            审查意见全文（中文，可能包含 markdown）
 * @param suggestedScript 从审查意见里抽出的第一个代码块内容；没有则为 null
 * @param available       本次是否真的调通了大模型；false 表示走了降级提示
 */
public record AiReviewResult(String text, String suggestedScript, boolean available) {}

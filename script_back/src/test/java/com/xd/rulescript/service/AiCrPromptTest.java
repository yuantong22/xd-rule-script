package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * AI CR 系统提示词的内容约束。
 *
 * CR_SYSTEM_PROMPT 是 package-private，本测试类在同 package 下访问它。
 * 大模型调用本身不进单测（dev-conventions），这里只锁提示词文本层面的关键约束，
 * 防止以后被人误改回去。
 */
class AiCrPromptTest {

    @Test
    void 系统提示词交代占位符不是错误() {
        // 已有约束的兜底：不交代 ${xxx} 是占位符的话，模型每次都会报「变量未定义」，审查意见全是噪音
        assertThat(AiService.CR_SYSTEM_PROMPT)
                .contains("占位符")
                .contains("${变量名}");
    }

    @Test
    void 系统提示词交代运行时没有context等全局对象() {
        // 修 bug 起源：AI 曾建议用户用 context.put(...) 写回结果，但本项目沙箱运行时
        // 只把用户填的占位符注入 binding，没有 context / log / out 等隐式全局对象，
        // 运行时会抛 MissingPropertyException。CR 提示词必须明确交代这一点，
        // 才能让 AI 抓到脚本里引用未声明变量的问题
        assertThat(AiService.CR_SYSTEM_PROMPT)
                .contains("context")
                .containsAnyOf("binding", "全局对象", "隐式对象");
    }

    @Test
    void 系统提示词把未声明变量列入审查项() {
        // 审查项必须显式包含「未声明变量」，否则 AI 只会关注语义/逻辑/空指针，
        // 遇到 context.put(...) 这种未声明变量的用法会视而不见
        assertThat(AiService.CR_SYSTEM_PROMPT).contains("未声明");
    }
}

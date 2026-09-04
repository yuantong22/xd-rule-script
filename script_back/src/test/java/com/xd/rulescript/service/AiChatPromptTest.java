package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 对话上下文组装（需求 4.3.4：每次发消息自动把当前编辑器脚本作为上下文）。
 */
class AiChatPromptTest {

    @Test
    void 有脚本时同时带上脚本与问题() {
        String text = AiService.buildChatUserText("这个脚本有什么问题", "int age = ${age}\nreturn age");

        assertThat(text).contains("int age = ${age}");
        assertThat(text).contains("这个脚本有什么问题");
    }

    @Test
    void 脚本与问题有明确分隔标记() {
        // 模型必须能分清哪段是脚本、哪段是提问，否则会把问题当代码审查
        String text = AiService.buildChatUserText("帮我改一下", "return 1");

        assertThat(text).contains("【当前编辑器中的脚本】");
        assertThat(text).contains("【用户的问题】");
        assertThat(text.indexOf("【当前编辑器中的脚本】"))
                .isLessThan(text.indexOf("帮我改一下"));
    }

    @Test
    void 脚本被代码块包裹() {
        String text = AiService.buildChatUserText("看看", "return 1");
        assertThat(text).contains("```groovy");
    }

    @Test
    void 占位符原样保留() {
        // 最关键的一条：${age} 不能被吃掉或转义
        String text = AiService.buildChatUserText("q", "int a = ${age}\nString s = \"${level}\"");
        assertThat(text).contains("${age}").contains("${level}");
    }

    @Test
    void 脚本为空时明确告知而不是留空白() {
        String text = AiService.buildChatUserText("帮我写个规则", "");

        assertThat(text).contains("帮我写个规则");
        assertThat(text).contains("编辑器为空");
        // 空脚本时不该出现空的代码块，那会让模型以为用户给了个空文件
        assertThat(text).doesNotContain("```groovy\n```");
    }

    @Test
    void 脚本为null时按空处理不抛异常() {
        assertThat(AiService.buildChatUserText("问", null)).contains("编辑器为空");
    }

    @Test
    void 脚本只有空白时按空处理() {
        assertThat(AiService.buildChatUserText("问", "   \n  ")).contains("编辑器为空");
    }

    @Test
    void 用户问题为空时仍有脚本上下文() {
        String text = AiService.buildChatUserText("", "return 1");
        assertThat(text).contains("return 1");
    }

    @Test
    void 用户问题里的占位符不被当脚本解析() {
        String text = AiService.buildChatUserText("${x} 是什么意思", "return 1");
        assertThat(text).contains("${x} 是什么意思");
    }

    @Test
    void 脚本里已含代码块时不破坏结构() {
        // 用户可能在脚本注释里写了 ```，组装后不该产生歧义的嵌套
        String script = "// ```groovy\nreturn 1";
        String text = AiService.buildChatUserText("q", script);
        assertThat(text).contains("return 1");
    }

    @Test
    void 系统提示词交代了沙箱限制() {
        // 不交代的话模型会建议 import 或访问文件，而那些必然被任务 10 的沙箱拦截
        assertThat(AiService.CHAT_SYSTEM_PROMPT)
                .contains("Groovy")
                .contains("占位符")
                .contains("沙箱");
    }

    @Test
    void 系统提示词要求脚本放代码块且保留占位符() {
        assertThat(AiService.CHAT_SYSTEM_PROMPT)
                .contains("```groovy")
                .contains("${变量名}");
    }

    @Test
    void 系统提示词要求中文回答() {
        assertThat(AiService.CHAT_SYSTEM_PROMPT).contains("中文");
    }

    @Test
    void 系统提示词交代运行时没有context等全局对象() {
        // 修 bug 起源：AI 曾建议用户用 context.put(...) 写回结果，但本项目沙箱运行时
        // 只把用户填的占位符注入 binding，没有 context / log / out 等隐式全局对象，
        // 运行时会抛 MissingPropertyException。提示词必须明确交代，否则 AI 继续幻觉
        assertThat(AiService.CHAT_SYSTEM_PROMPT)
                .contains("context")
                .containsAnyOf("binding", "全局对象", "隐式对象");
    }
}

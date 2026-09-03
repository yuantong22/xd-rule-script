package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 从大模型回复里提取「修改后的完整脚本」。
 * 模型输出格式不稳定，这里的边界情况必须全部钉死 ——
 * 提取错了会让用户一键把半截脚本替换进编辑器。
 */
class AiCodeBlockExtractorTest {

    @Test
    void 提取带groovy标记的代码块() {
        String reply = "审查发现两个问题：\n```groovy\nint a = 1\nreturn a\n```\n以上为修改后的脚本";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("int a = 1\nreturn a");
    }

    @Test
    void 提取无语言标记的代码块() {
        String reply = "建议改成：\n```\nreturn 1\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("return 1");
    }

    @Test
    void java标记也接受() {
        // 模型有时会把 Groovy 标成 java，不该因此提不到
        String reply = "```java\nreturn 2\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("return 2");
    }

    @Test
    void 多个块时取最后一个groovy块() {
        // 模型常先给「问题片段」再给「完整脚本」，完整的那个在后面
        String reply = """
                问题在这一行：
                ```groovy
                return a
                ```
                完整修改后脚本：
                ```groovy
                int a = ${age}
                return a * 2
                ```
                """;
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("int a = ${age}\nreturn a * 2");
    }

    @Test
    void 有groovy块时优先于无标记块() {
        String reply = "```\n不是脚本\n```\n```groovy\nreturn 1\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("return 1");
    }

    @Test
    void 占位符原样保留不被吃掉() {
        // 最关键的一条：${age} 必须完整出现在提取结果里
        String reply = "```groovy\nint age = ${age}\nString lv = \"${level}\"\nreturn age\n```";
        String extracted = AiCodeBlockExtractor.extract(reply);
        assertThat(extracted).contains("${age}").contains("${level}");
    }

    @Test
    void 没有代码块返回null() {
        assertThat(AiCodeBlockExtractor.extract("审查通过，未发现明显问题")).isNull();
    }

    @Test
    void 空代码块返回null() {
        assertThat(AiCodeBlockExtractor.extract("```groovy\n```")).isNull();
        assertThat(AiCodeBlockExtractor.extract("```groovy\n   \n```")).isNull();
    }

    @Test
    void 只有开头没有闭合返回null() {
        // 模型输出被截断时会这样；把后面全部内容当脚本会毁掉用户的编辑器
        assertThat(AiCodeBlockExtractor.extract("```groovy\nint a = 1\n（输出到此中断）")).isNull();
    }

    @Test
    void CRLF换行也能提取() {
        String reply = "```groovy\r\nint a = 1\r\nreturn a\r\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("int a = 1\nreturn a");
    }

    @Test
    void 语言标记后有多余空格也能提取() {
        assertThat(AiCodeBlockExtractor.extract("```groovy   \nreturn 1\n```")).isEqualTo("return 1");
    }

    @Test
    void 首尾空白被trim() {
        assertThat(AiCodeBlockExtractor.extract("```groovy\n\n  return 1  \n\n```")).isEqualTo("return 1");
    }

    @Test
    void 入参为null或空返回null且不抛异常() {
        assertThat(AiCodeBlockExtractor.extract(null)).isNull();
        assertThat(AiCodeBlockExtractor.extract("")).isNull();
        assertThat(AiCodeBlockExtractor.extract("   ")).isNull();
    }

    @Test
    void 反引号多于三个也能提取() {
        // 模型偶尔用 ```` 包裹含 ``` 的内容
        assertThat(AiCodeBlockExtractor.extract("````groovy\nreturn 1\n````")).isEqualTo("return 1");
    }

    @Test
    void 行内单反引号不被误判为代码块() {
        assertThat(AiCodeBlockExtractor.extract("建议把 `level` 改名")).isNull();
    }
}

package com.xd.rulescript.groovy;

import static com.xd.rulescript.groovy.GroovyLexScanner.Region.CODE;
import static com.xd.rulescript.groovy.GroovyLexScanner.Region.DOUBLE_QUOTED;
import static com.xd.rulescript.groovy.GroovyLexScanner.Region.SINGLE_QUOTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.groovy.GroovyLexScanner.Region;
import org.junit.jupiter.api.Test;

/**
 * 区域扫描是占位符替换的依据，这里的每个用例都对应一种真实脚本写法。
 */
class GroovyLexScannerTest {

    /** 返回子串第一个字符所属的区域，方便断言 */
    private static Region regionAt(String script, String needle) {
        int index = script.indexOf(needle);
        assertThat(index).as("脚本里应该能找到 %s", needle).isGreaterThanOrEqualTo(0);
        return GroovyLexScanner.scan(script)[index];
    }

    @Test
    void 返回数组与脚本等长() {
        String script = "int age = ${age}";
        assertThat(GroovyLexScanner.scan(script)).hasSize(script.length());
    }

    @Test
    void 空脚本不报错() {
        assertThat(GroovyLexScanner.scan("")).isEmpty();
    }

    @Test
    void 代码区的占位符归为CODE() {
        assertThat(regionAt("int age = ${age}", "${age}")).isEqualTo(CODE);
    }

    @Test
    void 双引号串内的占位符归为DOUBLE_QUOTED() {
        String script = "String level = \"${level}\"";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
        // 引号字符本身也算串内
        assertThat(regionAt(script, "\"${")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 双引号串内嵌入文字时依然归为DOUBLE_QUOTED() {
        assertThat(regionAt("return \"等级：${level}，年龄：${age}\"", "${level}")).isEqualTo(DOUBLE_QUOTED);
        assertThat(regionAt("return \"等级：${level}，年龄：${age}\"", "${age}")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 单引号串内归为SINGLE_QUOTED() {
        assertThat(regionAt("def s = '等级${level}'", "${level}")).isEqualTo(SINGLE_QUOTED);
    }

    @Test
    void 三引号字符串整体标记() {
        String script = "def s = \"\"\"\n多行 ${level}\n\"\"\"";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 字符串结束后回到CODE() {
        String script = "String level = \"${level}\"\nint age = ${age}";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
        assertThat(regionAt(script, "${age}")).isEqualTo(CODE);
    }

    @Test
    void 转义引号不会提前结束字符串() {
        // \" 是串内的转义引号，后面的 ${level} 仍在串内
        String script = "def s = \"他说\\\"你好\\\" ${level}\"";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 行注释里的内容归为CODE() {
        // 注释不当字符串处理：里面的 ${x} 替换后仍在注释里，无害
        assertThat(regionAt("// 说明 ${note}\nreturn 1", "${note}")).isEqualTo(CODE);
    }

    @Test
    void 块注释里的内容归为CODE() {
        assertThat(regionAt("/* 说明 ${note} */\nreturn 1", "${note}")).isEqualTo(CODE);
    }

    @Test
    void 块注释里的引号不会误开字符串() {
        // 如果把注释里的 " 当成字符串开头，后面的 ${age} 就会被误判为串内
        String script = "/* 不要写 \"xxx\" */\nint age = ${age}";
        assertThat(regionAt(script, "${age}")).isEqualTo(CODE);
    }

    @Test
    void 未闭合字符串扫到行尾不越界() {
        String script = "def s = \"没有闭合\ndef t = ${age}";
        Region[] regions = GroovyLexScanner.scan(script);
        assertThat(regions).hasSize(script.length());
        // 换行终止了未闭合的串，下一行回到代码区
        assertThat(regions[script.indexOf("${age}")]).isEqualTo(CODE);
    }
}

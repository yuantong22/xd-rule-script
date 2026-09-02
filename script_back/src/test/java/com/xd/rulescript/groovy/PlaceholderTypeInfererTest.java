package com.xd.rulescript.groovy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 类型推断决定前端渲染什么输入控件，推错会让用户填不进值。
 */
class PlaceholderTypeInfererTest {

    // ---------- normalize ----------

    @Test
    void 基本类型与包装类归一到int() {
        assertThat(PlaceholderTypeInferer.normalize("int")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("Integer")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("java.lang.Integer")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("short")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("byte")).isEqualTo("int");
    }

    @Test
    void 长整型归一到long() {
        assertThat(PlaceholderTypeInferer.normalize("long")).isEqualTo("long");
        assertThat(PlaceholderTypeInferer.normalize("Long")).isEqualTo("long");
        assertThat(PlaceholderTypeInferer.normalize("java.math.BigInteger")).isEqualTo("long");
    }

    @Test
    void 浮点与大数归一到double() {
        assertThat(PlaceholderTypeInferer.normalize("double")).isEqualTo("double");
        assertThat(PlaceholderTypeInferer.normalize("float")).isEqualTo("double");
        assertThat(PlaceholderTypeInferer.normalize("BigDecimal")).isEqualTo("double");
        assertThat(PlaceholderTypeInferer.normalize("Number")).isEqualTo("double");
    }

    @Test
    void 布尔归一到boolean() {
        assertThat(PlaceholderTypeInferer.normalize("boolean")).isEqualTo("boolean");
        assertThat(PlaceholderTypeInferer.normalize("java.lang.Boolean")).isEqualTo("boolean");
    }

    @Test
    void 字符序列归一到String() {
        assertThat(PlaceholderTypeInferer.normalize("String")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("java.lang.String")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("CharSequence")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("char")).isEqualTo("String");
    }

    @Test
    void 认不出的类型一律String() {
        assertThat(PlaceholderTypeInferer.normalize("def")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("var")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("Object")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("java.util.List")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize(null)).isEqualTo("String");
    }

    // ---------- infer ----------

    @Test
    void 从声明语句推断出各类型() {
        String script = """
                int age = ${age}
                long ts = ${ts}
                double rate = ${rate}
                boolean vip = ${vip}
                String name = ${name}
                return age
                """;
        assertThat(PlaceholderTypeInferer.infer(script)).containsExactly(
                Map.entry("age", "int"),
                Map.entry("ts", "long"),
                Map.entry("rate", "double"),
                Map.entry("vip", "boolean"),
                Map.entry("name", "String"));
    }

    @Test
    void 没有声明的占位符按String() {
        assertThat(PlaceholderTypeInferer.infer("return ${age} + 1"))
                .containsEntry("age", "String");
    }

    @Test
    void def声明按String() {
        assertThat(PlaceholderTypeInferer.infer("def age = ${age}\nreturn age"))
                .containsEntry("age", "String");
    }

    @Test
    void 串内的占位符推断不到声明按String() {
        // "${level}" 转成 "__ph_level" 后落在字符串字面量里，不是变量表达式，所以推不出来
        assertThat(PlaceholderTypeInferer.infer("String level = \"${level}\"\nreturn level"))
                .containsEntry("level", "String");
    }

    @Test
    void 同一占位符多处出现只保留一条且以首个声明为准() {
        String script = "int age = ${age}\nreturn ${age} * 2";
        assertThat(PlaceholderTypeInferer.infer(script))
                .hasSize(1)
                .containsEntry("age", "int");
    }

    @Test
    void 返回顺序与脚本中出现顺序一致() {
        String script = "String b = ${beta}\nint a = ${alpha}\nreturn 1";
        assertThat(PlaceholderTypeInferer.infer(script).keySet())
                .containsExactly("beta", "alpha");
    }

    @Test
    void 无占位符返回空map() {
        assertThat(PlaceholderTypeInferer.infer("return 1 + 1")).isEmpty();
    }

    @Test
    void 空脚本与null不报错() {
        assertThat(PlaceholderTypeInferer.infer("")).isEmpty();
        assertThat(PlaceholderTypeInferer.infer(null)).isEmpty();
    }

    @Test
    void 语法错误的脚本不抛异常而是全部按String() {
        // 推断跑在语法校验之前，脚本可能是坏的；这里必须让路，把行号错误留给语法校验去报
        String broken = "int age = ${age}\nreturn ((( ";
        assertThat(PlaceholderTypeInferer.infer(broken)).containsEntry("age", "String");
    }

    @Test
    void 方法参数位置的占位符按String() {
        assertThat(PlaceholderTypeInferer.infer("println(${msg})\nreturn 1"))
                .containsEntry("msg", "String");
    }

    @Test
    void 声明在方法体内也能推断() {
        String script = """
                def calc() {
                    int age = ${age}
                    return age
                }
                return calc()
                """;
        assertThat(PlaceholderTypeInferer.infer(script)).containsEntry("age", "int");
    }
}

package com.xd.rulescript.groovy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 每个用例对应一种真实脚本写法，重点盯住「代码区 vs 串内」的差异与转义。
 */
class PlaceholderSubstitutorTest {

    private static Map<String, String> types(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> params(String... kv) {
        return types(kv);
    }

    // ---------- extractNames ----------

    @Test
    void 提取占位符按出现顺序去重() {
        String script = "int age = ${age}\nString level = \"${level}\"\nreturn ${age} + ${level}";
        assertThat(PlaceholderSubstitutor.extractNames(script)).containsExactly("age", "level");
    }

    @Test
    void 无占位符返回空列表() {
        assertThat(PlaceholderSubstitutor.extractNames("return 1 + 1")).isEmpty();
    }

    @Test
    void 空脚本返回空列表() {
        assertThat(PlaceholderSubstitutor.extractNames("")).isEmpty();
    }

    @Test
    void 不完整的占位符语法不提取() {
        // 变量名必须是 \w+，${}、${a-b}、$age 都不算
        assertThat(PlaceholderSubstitutor.extractNames("return \"${}\" + \"$age\" + \"${a-b}\"")).isEmpty();
    }

    @Test
    void 注释里的占位符照样提取() {
        // 设计约定：注释区归 CODE，提取出来无害（替换后仍在注释里，不影响编译）
        assertThat(PlaceholderSubstitutor.extractNames("// 用到 ${age}\nreturn 1")).containsExactly("age");
    }

    // ---------- toIdentifiers ----------

    @Test
    void 转成假标识符供AST使用() {
        assertThat(PlaceholderSubstitutor.toIdentifiers("int age = ${age}"))
                .isEqualTo("int age = __ph_age");
    }

    @Test
    void 串内的占位符也转成标识符且不破坏引号() {
        assertThat(PlaceholderSubstitutor.toIdentifiers("String level = \"${level}\""))
                .isEqualTo("String level = \"__ph_level\"");
    }

    // ---------- toFakeValues（语法校验用）----------

    @Test
    void 代码区假值按类型填充() {
        String script = "int a = ${age}\nlong b = ${ts}\ndouble c = ${rate}\nboolean d = ${vip}\nString e = ${name}";
        Map<String, String> t = types("age", "int", "ts", "long", "rate", "double", "vip", "boolean", "name", "String");
        assertThat(PlaceholderSubstitutor.toFakeValues(script, t))
                .isEqualTo("int a = 0\nlong b = 0L\ndouble c = 0.0d\nboolean d = false\nString e = \"\"");
    }

    @Test
    void 双引号串内假值不再套引号() {
        assertThat(PlaceholderSubstitutor.toFakeValues("String level = \"${level}\"", types("level", "String")))
                .isEqualTo("String level = \"\"");
    }

    @Test
    void 串内嵌入文字时假值直接拼接() {
        assertThat(PlaceholderSubstitutor.toFakeValues("return \"等级：${level}\"", types("level", "String")))
                .isEqualTo("return \"等级：\"");
    }

    @Test
    void 类型缺失时按String处理() {
        assertThat(PlaceholderSubstitutor.toFakeValues("def x = ${unknown}", Map.of()))
                .isEqualTo("def x = \"\"");
    }

    // ---------- toLiterals（运行用）----------

    @Test
    void 代码区数字直接落字面量() {
        assertThat(PlaceholderSubstitutor.toLiterals("return ${age} * 2", params("age", "28"), types("age", "int")))
                .isEqualTo("return 28 * 2");
    }

    @Test
    void long补L后缀double补d后缀() {
        String script = "return ${ts} + ${rate}";
        assertThat(PlaceholderSubstitutor.toLiterals(script,
                params("ts", "1700000000", "rate", "1.5"),
                types("ts", "long", "rate", "double")))
                .isEqualTo("return 1700000000L + 1.5d");
    }

    @Test
    void 串内值不加引号() {
        assertThat(PlaceholderSubstitutor.toLiterals("String level = \"${level}\"",
                params("level", "vip"), types("level", "String")))
                .isEqualTo("String level = \"vip\"");
    }

    @Test
    void 代码区String值要加引号() {
        assertThat(PlaceholderSubstitutor.toLiterals("String level = ${level}",
                params("level", "vip"), types("level", "String")))
                .isEqualTo("String level = \"vip\"");
    }

    @Test
    void 串内值含双引号要转义() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"他说：${msg}\"",
                params("msg", "\"你好\""), types("msg", "String")))
                .isEqualTo("return \"他说：\\\"你好\\\"\"");
    }

    @Test
    void 串内值含美元符号要转义防止二次插值() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"价格：${price}\"",
                params("price", "$100"), types("price", "String")))
                .isEqualTo("return \"价格：\\$100\"");
    }

    @Test
    void 单引号串内美元符号不转义() {
        // Groovy 单引号串不做插值，写成 \$ 反而会多出一个反斜杠
        assertThat(PlaceholderSubstitutor.toLiterals("return '价格：${price}'",
                params("price", "$100"), types("price", "String")))
                .isEqualTo("return '价格：$100'");
    }

    @Test
    void 单引号串内单引号要转义() {
        assertThat(PlaceholderSubstitutor.toLiterals("return '它是${what}'",
                params("what", "a'b"), types("what", "String")))
                .isEqualTo("return '它是a\\'b'");
    }

    @Test
    void 值里的换行转成转义序列() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"${msg}\"",
                params("msg", "第一行\n第二行"), types("msg", "String")))
                .isEqualTo("return \"第一行\\n第二行\"");
    }

    @Test
    void 负数与小数正常通过() {
        assertThat(PlaceholderSubstitutor.toLiterals("return ${a} + ${b}",
                params("a", "-5", "b", "-0.25"), types("a", "int", "b", "double")))
                .isEqualTo("return -5 + -0.25d");
    }

    // ---------- 错误路径 ----------

    @Test
    void 缺少填值抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${age}",
                Map.of(), types("age", "int")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("age")
                .hasMessageContaining("缺少");
    }

    @Test
    void int填入非数字抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${age}",
                params("age", "abc"), types("age", "int")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("age")
                .hasMessageContaining("整数");
    }

    @Test
    void double填入非数字抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${rate}",
                params("rate", "1.2.3"), types("rate", "double")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("小数");
    }

    @Test
    void boolean填入非布尔抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${vip}",
                params("vip", "yes"), types("vip", "boolean")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("true")
                .hasMessageContaining("false");
    }

    @Test
    void 填值为空串对String合法() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"${msg}\"",
                params("msg", ""), types("msg", "String")))
                .isEqualTo("return \"\"");
    }

    @Test
    void 多余的填值被忽略() {
        assertThat(PlaceholderSubstitutor.toLiterals("return ${age}",
                params("age", "28", "extra", "x"), types("age", "int")))
                .isEqualTo("return 28");
    }
}

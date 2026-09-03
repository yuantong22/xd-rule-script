package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.config.GroovySandboxConfig;
import com.xd.rulescript.config.ScriptExecutorConfig;
import com.xd.rulescript.dto.RunResult;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 沙箱运行的返回值与异常翻译。超时与拦截的用例在 GroovyEngineSandboxTest 里，
 * 因为它们要么慢（真等超时）要么要单独的引擎实例。
 */
class GroovyEngineRunTest {

    private static ExecutorService executor;
    private static GroovyEngineService engine;

    @BeforeAll
    static void setUp() {
        executor = ScriptExecutorConfig.build();
        engine = new GroovyEngineService(GroovySandboxConfig.build(), executor, 5);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 正常返回 ----------

    @Test
    void 返回整数() {
        RunResult r = engine.run("return 1 + 2", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("3");
        assertThat(r.errorMessage()).isNull();
        assertThat(r.timeout()).isFalse();
    }

    @Test
    void 返回字符串() {
        assertThat(engine.run("return \"你好，世界\"", Map.of()).value()).isEqualTo("你好，世界");
    }

    @Test
    void 占位符真值代入后计算正确() {
        RunResult r = engine.run("int age = ${age}\nreturn age >= 18 ? \"成年\" : \"未成年\"",
                Map.of("age", "28"));
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("成年");
    }

    @Test
    void 串内占位符替换后不带多余引号() {
        RunResult r = engine.run("String level = \"${level}\"\nreturn \"等级：\" + level",
                Map.of("level", "vip"));
        assertThat(r.value()).isEqualTo("等级：vip");
    }

    @Test
    void 串内值含美元符号不被二次插值() {
        RunResult r = engine.run("return \"价格：${price}\"", Map.of("price", "$100"));
        assertThat(r.value()).isEqualTo("价格：$100");
    }

    @Test
    void 多类型混合填值() {
        RunResult r = engine.run("""
                int age = ${age}
                long ts = ${ts}
                double rate = ${rate}
                boolean vip = ${vip}
                String name = ${name}
                return "${name}:" + age + ":" + rate + ":" + vip + ":" + (ts > 0)
                """,
                Map.of("age", "28", "ts", "1700000000", "rate", "0.85", "vip", "true", "name", "张三"));
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("张三:28:0.85:true:true");
    }

    @Test
    void 集合与闭包能正常跑() {
        RunResult r = engine.run("def list = [1, 2, 3]\nreturn list.collect { it * it }.sum()", Map.of());
        assertThat(r.value()).isEqualTo("14");
    }

    @Test
    void 无返回值的脚本给空串而不是null() {
        // 末尾裸 return 才真正无返回值：Groovy 脚本默认返回最后一个表达式的值，a = a + 1 会返回 2
        RunResult r = engine.run("def a = 1\na = a + 1\nreturn", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("");
    }

    @Test
    void 返回Map时转成字符串() {
        RunResult r = engine.run("return [a: 1, b: 2]", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).contains("a").contains("1");
    }

    // ---------- 填值错误 ----------

    @Test
    void 缺少填值给中文提示() {
        RunResult r = engine.run("return ${age}", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("缺少").contains("age");
        assertThat(r.timeout()).isFalse();
    }

    @Test
    void int填入字母给中文提示() {
        RunResult r = engine.run("int age = ${age}\nreturn age", Map.of("age", "abc"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("整数");
    }

    @Test
    void params传null给中文提示() {
        RunResult r = engine.run("return ${age}", null);
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("缺少");
    }

    // ---------- 运行期异常翻译 ----------

    @Test
    void 除以零翻译成中文() {
        RunResult r = engine.run("return 1 / 0", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("0");
        assertThat(r.errorMessage()).doesNotContain("ArithmeticException");
    }

    @Test
    void 空指针翻译成中文() {
        RunResult r = engine.run("def x = null\nreturn x.length()", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("空值");
        assertThat(r.errorMessage()).doesNotContain("NullPointerException");
    }

    @Test
    void 未定义变量翻译成中文并带变量名() {
        RunResult r = engine.run("return notDefinedVariable", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("notDefinedVariable");
        assertThat(r.errorMessage()).doesNotContain("MissingPropertyException");
    }

    @Test
    void 调用不存在的方法翻译成中文() {
        RunResult r = engine.run("return \"abc\".thisMethodDoesNotExist()", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).doesNotContain("MissingMethodException");
    }

    @Test
    void 下标越界翻译成中文() {
        // 用 .get(5) 而非 [5]：Groovy 列表 list[5] 越界返回 null 不抛异常，.get(5) 才真抛 IndexOutOfBoundsException
        RunResult r = engine.run("def list = [1]\nreturn list.get(5)", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).doesNotContain("IndexOutOfBoundsException");
    }

    @Test
    void 递归爆栈不会把服务搞挂() {
        RunResult r = engine.run("def f() { return f() }\nreturn f()", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).isNotBlank();
        // 关键：异常之后引擎还能继续用
        assertThat(engine.run("return 1", Map.of()).success()).isTrue();
    }

    @Test
    void 错误信息里不含堆栈() {
        String message = engine.run("return 1 / 0", Map.of()).errorMessage();
        assertThat(message).doesNotContain("\tat ").doesNotContain("at org.codehaus");
    }

    @Test
    void 运行期异常后引擎仍可继续使用() {
        engine.run("def x = null\nreturn x.length()", Map.of());
        engine.run("return 1 / 0", Map.of());
        assertThat(engine.run("return 42", Map.of()).value()).isEqualTo("42");
    }

    // ---------- stringify ----------

    @Test
    void null转空串() {
        assertThat(GroovyEngineService.stringify(null)).isEqualTo("");
    }

    @Test
    void 超长结果被截断() {
        RunResult r = engine.run("return 'a' * 50000", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).contains("截断");
        assertThat(r.value().length()).isLessThan(20000);
    }
}

package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.config.GroovySandboxConfig;
import com.xd.rulescript.config.ScriptExecutorConfig;
import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.groovy.ScriptSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 语法校验与安全拦截。刻意不用 @SpringBootTest —— GroovyEngineService 只依赖
 * CompilerConfiguration、ExecutorService 和一个 int，手动 new 出来测更快也更稳。
 */
class GroovyEngineSyntaxTest {

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

    // ---------- 语法校验：通过路径 ----------

    @Test
    void 合法脚本语法通过() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nreturn a + 1");
        assertThat(r.ok()).isTrue();
        assertThat(r.line()).isNull();
        assertThat(r.message()).isNull();
    }

    @Test
    void 空脚本语法通过() {
        assertThat(engine.checkSyntax("").ok()).isTrue();
    }

    @Test
    void 含占位符的脚本按假值替换后能通过() {
        // int age = ${age} 原文是编译不过的，替换成 int age = 0 才行
        assertThat(engine.checkSyntax("int age = ${age}\nreturn age * 2").ok()).isTrue();
    }

    @Test
    void 串内占位符也能通过() {
        assertThat(engine.checkSyntax("String level = \"${level}\"\nreturn \"等级：${level}\"").ok()).isTrue();
    }

    @Test
    void 闭包与集合操作不误伤() {
        String script = """
                def list = [3, 1, 2]
                def sorted = list.collect { it * 2 }.findAll { it > 2 }
                return sorted.join(",")
                """;
        assertThat(engine.checkSyntax(script).ok()).isTrue();
    }

    @Test
    void 自定义方法定义不误伤() {
        String script = """
                def calc(int x) { return x * 2 }
                return calc(${n})
                """;
        assertThat(engine.checkSyntax(script).ok()).isTrue();
    }

    // ---------- 语法校验：错误路径 ----------

    @Test
    void 括号不闭合给出行号与中文说明() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nreturn (a + 1");
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).isNotNull();
        assertThat(r.message()).isNotBlank();
        // 必须是中文可读提示，不能只是英文原文
        assertThat(r.message()).containsAnyOf("没有写完", "缺少", "不该有的符号", "语法错误");
    }

    @Test
    void 多余右括号报出准确行号() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nint b = 2\nreturn a + b))");
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).isEqualTo(3);
    }

    @Test
    void 动态类型不匹配在语法校验放行() {
        // 动态 Groovy 里 int age = "" 能正常编译，类型不符只在运行时才抛 GroovyCastException。
        // checkSyntax 只 parse 不执行（靠填假值让脚本能 parse），语法层拦不到也不该拦；
        // 值/类型不匹配属运行期职责，由任务 11 的 toLiterals + 运行时异常翻译覆盖并测试。
        SyntaxCheckResult r = engine.checkSyntax("String name = ${name}\nint age = ${name}\nreturn age");
        assertThat(r.ok()).isTrue();
    }

    @Test
    void 错误信息不含异常堆栈() {
        String message = engine.checkSyntax("return ((( ").message();
        assertThat(message).doesNotContain("at org.codehaus").doesNotContain("\tat ");
    }

    @Test
    void 错误信息不含英文异常类名() {
        String message = engine.checkSyntax("int a = \nreturn a").message();
        assertThat(message).doesNotContain("MultipleCompilationErrorsException");
        assertThat(message).doesNotContain("SyntaxException");
    }

    // ---------- 安全拦截 ----------

    @Test
    void 拦截System调用() {
        SyntaxCheckResult r = engine.checkSyntax("System.exit(0)\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
        assertThat(r.message()).contains("System");
        assertThat(r.line()).isEqualTo(1);
    }

    @Test
    void 拦截Runtime执行外部命令() {
        SyntaxCheckResult r = engine.checkSyntax("Runtime.getRuntime().exec(\"ls\")\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截文件访问() {
        SyntaxCheckResult r = engine.checkSyntax("def f = new File(\"/etc/passwd\")\nreturn f.text");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
        assertThat(r.message()).contains("File");
    }

    @Test
    void 拦截网络访问() {
        SyntaxCheckResult r = engine.checkSyntax("def u = new URL(\"http://x.com\")\nreturn u.text");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截自建线程() {
        SyntaxCheckResult r = engine.checkSyntax("new Thread({ println 1 }).start()\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截import语句() {
        SyntaxCheckResult r = engine.checkSyntax("import java.io.File\nreturn new File(\"x\").exists()");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
        assertThat(r.message()).contains("import");
    }

    @Test
    void 拦截package声明() {
        SyntaxCheckResult r = engine.checkSyntax("package com.evil\nreturn 1");
        assertThat(r.ok()).isFalse();
    }

    @Test
    void 拦截反射调用() {
        SyntaxCheckResult r = engine.checkSyntax(
                "def m = String.class.getMethod(\"toString\")\nreturn m.invoke(\"x\")");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截先赋值给变量再调用的绕法() {
        // SecureASTCustomizer 抓不到这种，靠自研 customizer 按名字匹配兜住
        SyntaxCheckResult r = engine.checkSyntax("def s = System\ns.exit(0)\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 多处违规时报第一处并给出行号() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nSystem.exit(0)\nnew File(\"x\")\nreturn a");
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).isEqualTo(2);
    }

    // ---------- 过度拦截的已知边界（钉住取舍，改动前必须知道会红）----------

    @Test
    void 变量名恰好叫File会被误伤() {
        // 已知取舍：黑名单按简单类名匹配。规则脚本里这么命名的概率极低，漏拦代价更高
        SyntaxCheckResult r = engine.checkSyntax("def File = 1\nreturn File");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 小写变量名file不受影响() {
        assertThat(engine.checkSyntax("def file = \"报告.txt\"\nreturn file.length()").ok()).isTrue();
    }

    // ---------- 占位符提取 ----------

    @Test
    void 提取占位符带类型() {
        List<PlaceholderInfo> list = engine.extractPlaceholders(
                "int age = ${age}\nString level = ${level}\nreturn age");
        assertThat(list).containsExactly(
                new PlaceholderInfo("age", "int"),
                new PlaceholderInfo("level", "String"));
    }

    @Test
    void 无占位符返回空列表() {
        assertThat(engine.extractPlaceholders("return 1 + 1")).isEmpty();
    }

    @Test
    void 语法错误的脚本仍能提取占位符() {
        // 提取不依赖编译成功，类型推断失败时兜底为 String
        assertThat(engine.extractPlaceholders("int age = ${age}\nreturn ((( "))
                .containsExactly(new PlaceholderInfo("age", "String"));
    }
}

package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.config.GroovySandboxConfig;
import com.xd.rulescript.config.ScriptExecutorConfig;
import com.xd.rulescript.dto.RunResult;
import com.xd.rulescript.groovy.ScriptSecurityException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 沙箱的两条硬指标：死循环必须能在超时后真的停下来（线程不泄漏），
 * 危险操作必须在运行前就被拦住。
 *
 * <p>超时用 1 秒的独立引擎实例，避免整个测试套件被拖慢；生产值是 5 秒（app.script.timeout-seconds）。
 */
class GroovyEngineSandboxTest {

    private static ExecutorService executor;
    private static GroovyEngineService fastEngine;

    @BeforeAll
    static void setUp() {
        executor = ScriptExecutorConfig.build();
        fastEngine = new GroovyEngineService(GroovySandboxConfig.build(), executor, 1);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 超时 ----------

    @Test
    void 死循环超时被中断() {
        RunResult r = fastEngine.run("while (true) { }", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.timeout()).isTrue();
        assertThat(r.errorMessage()).contains("秒").contains("中断");
    }

    @Test
    void for循环死转也能超时() {
        RunResult r = fastEngine.run("for (;;) { }", Map.of());
        assertThat(r.timeout()).isTrue();
    }

    @Test
    void 闭包内的死循环也能超时() {
        RunResult r = fastEngine.run("def c = { while (true) { } }\nc.call()\nreturn 1", Map.of());
        assertThat(r.timeout()).isTrue();
    }

    @Test
    void 超时后线程被归还不会泄漏() throws InterruptedException {
        fastEngine.run("while (true) { }", Map.of());

        // 这条是整个沙箱设计的关键验证：@ThreadInterrupt 往循环里注入了中断检查点，
        // future.cancel(true) 才能真正停掉线程。没有它，线程会一直空转，跑十几次池子就废了。
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        long deadline = System.currentTimeMillis() + 3000;
        while (pool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(pool.getActiveCount()).as("超时后线程应被归还").isZero();
    }

    @Test
    void 连续超时后引擎依然可用() {
        for (int i = 0; i < 3; i++) {
            assertThat(fastEngine.run("while (true) { }", Map.of()).timeout()).isTrue();
        }
        RunResult r = fastEngine.run("return 1 + 1", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("2");
    }

    @Test
    void 正常脚本不受超时逻辑影响() {
        RunResult r = fastEngine.run("def sum = 0\nfor (int i = 0; i < 1000; i++) { sum += i }\nreturn sum",
                Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("499500");
        assertThat(r.timeout()).isFalse();
    }

    // ---------- 运行前的安全拦截 ----------

    @Test
    void 运行时同样拦截System() {
        RunResult r = fastEngine.run("System.exit(0)\nreturn 1", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
    }

    @Test
    void 运行时拦截文件读取() {
        RunResult r = fastEngine.run("return new File('/etc/passwd').text", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
        assertThat(r.errorMessage()).contains("文件");
    }

    @Test
    void 运行时拦截外部命令() {
        RunResult r = fastEngine.run("return Runtime.getRuntime().exec('ls').text", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
    }

    @Test
    void 运行时拦截网络请求() {
        RunResult r = fastEngine.run("return new URL('http://example.com').text", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
        assertThat(r.errorMessage()).contains("网络");
    }

    @Test
    void 拦截信息不含堆栈() {
        String message = fastEngine.run("System.exit(0)", Map.of()).errorMessage();
        assertThat(message).doesNotContain("\tat ").doesNotContain("MultipleCompilationErrorsException");
    }

    // ---------- 误伤检查 ----------

    @Test
    void 正常业务脚本不被沙箱误伤() {
        // discount 用 BigDecimal（不带 d 后缀）：0.8*0.9 在 double 下是 0.7200000000000001，BigDecimal 才精确等于 0.72
        String script = """
                int age = ${age}
                String level = "${level}"
                def discount = 1.0
                if (level == "vip") {
                    discount = 0.8
                }
                if (age >= 60) {
                    discount = discount * 0.9
                }
                def tags = ["年龄:" + age, "等级:" + level]
                return tags.join(" / ") + " 折扣:" + discount
                """;
        RunResult r = fastEngine.run(script, Map.of("age", "65", "level", "vip"));
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("年龄:65 / 等级:vip 折扣:0.72");
    }

    @Test
    void 字符串方法与数学函数不被误伤() {
        RunResult r = fastEngine.run(
                "def s = 'Hello World'\nreturn s.toLowerCase().replaceAll('o', '0') + Math.max(1, 2)", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("hell0 w0rld2");
    }

    @Test
    void 日期计算不被误伤() {
        RunResult r = fastEngine.run("return new Date(0).time", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("0");
    }
}

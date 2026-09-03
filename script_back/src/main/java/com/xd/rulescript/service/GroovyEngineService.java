package com.xd.rulescript.service;

import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.RunResult;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.groovy.PlaceholderSubstitutor;
import com.xd.rulescript.groovy.PlaceholderTypeInferer;
import com.xd.rulescript.groovy.PlaceholderValueException;
import com.xd.rulescript.groovy.ScriptSecurityException;
import groovy.lang.GroovyShell;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.syntax.SyntaxException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Groovy 引擎编排层：提取占位符、语法校验、沙箱运行。
 *
 * <p>本类不含算法（算法在 groovy 包里），只做三件事：按顺序调用、把异常翻译成中文、
 * 保证任何情况下都不把堆栈漏给前端。
 *
 * <p>构造参数全是普通类型，所以单测可以直接 new，不必起 Spring 上下文。
 */
@Service
public class GroovyEngineService {

    private final CompilerConfiguration compilerConfiguration;
    private final ExecutorService scriptExecutor;
    private final int timeoutSeconds;

    public GroovyEngineService(
            CompilerConfiguration groovyCompilerConfiguration,
            // 显式限定：任务 16 新增了第二个 ExecutorService bean（crExecutor），
            // 不加 @Qualifier 就只能依赖「参数名 == bean 名」的隐式回退匹配，
            // 脆弱（重命名即断）且一旦失效会让所有 @SpringBootTest 因 NoUniqueBeanDefinitionException 起不来
            @Qualifier("scriptExecutor") ExecutorService scriptExecutor,
            @Value("${app.script.timeout-seconds:5}") int timeoutSeconds) {
        this.compilerConfiguration = groovyCompilerConfiguration;
        this.scriptExecutor = scriptExecutor;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 提取脚本里的全部占位符及其类型，顺序与脚本中出现顺序一致。
     * 不依赖编译成功：脚本语法坏掉时类型推断会兜底为 String，仍能把名字列出来。
     */
    public List<PlaceholderInfo> extractPlaceholders(String script) {
        Map<String, String> types = PlaceholderTypeInferer.infer(script);
        List<PlaceholderInfo> result = new ArrayList<>(types.size());
        types.forEach((name, type) -> result.add(new PlaceholderInfo(name, type)));
        return result;
    }

    /**
     * 语法校验。流程：推断类型 → 按类型填假值 → 用沙箱配置编译（只编译不执行）。
     *
     * <p>先填假值再编译是必须的：int age = ${age} 原文根本编译不过。
     * 安全拦截也在这一步完成，所以「校验通过」就意味着「运行不会被沙箱挡住」。
     */
    public SyntaxCheckResult checkSyntax(String script) {
        if (script == null || script.isBlank()) {
            return SyntaxCheckResult.pass();
        }
        Map<String, String> types = PlaceholderTypeInferer.infer(script);
        String compilable = PlaceholderSubstitutor.toFakeValues(script, types);
        try {
            // 每次都新建 GroovyShell：它内部持有 GroovyClassLoader，每次 parse 都会生成新类。
            // 复用同一个 shell 会让类一直挂在同一个 loader 上，跑几百次就 metaspace 溢出
            new GroovyShell(compilerConfiguration).parse(compilable);
            return SyntaxCheckResult.pass();
        } catch (MultipleCompilationErrorsException e) {
            return fromCompileErrors(e);
        } catch (RuntimeException e) {
            return SyntaxCheckResult.error(null, "脚本无法编译，请检查写法后重试");
        }
    }

    /** 从 Groovy 的编译错误里取出第一条，转成「行号 + 中文说明」 */
    private static SyntaxCheckResult fromCompileErrors(MultipleCompilationErrorsException e) {
        ErrorCollector collector = e.getErrorCollector();
        if (collector == null) {
            return SyntaxCheckResult.error(null, "脚本存在错误，请检查后重试");
        }
        for (int i = 0; i < collector.getErrorCount(); i++) {
            Exception raw = collector.getException(i);
            SyntaxException syntax = unwrapSyntax(raw);
            if (syntax != null) {
                String message = syntax.getOriginalMessage();
                // 安全拦截的消息本来就是中文，直接透传；语法错误要翻译
                String readable = ScriptSecurityException.isSecurityMessage(message)
                        ? message
                        : humanizeCompileError(syntax);
                int line = syntax.getLine();
                return SyntaxCheckResult.error(line > 0 ? line : null, readable);
            }
        }
        return SyntaxCheckResult.error(null, "脚本存在错误，请检查后重试");
    }

    /**
     * ErrorCollector#getException 内部已经把 SyntaxErrorMessage 拆成了它的 cause（即 SyntaxException），
     * 所以拿到的 raw 通常直接就是 SyntaxException；不是的话返回 null 让上层兜底。
     */
    private static SyntaxException unwrapSyntax(Exception raw) {
        return raw instanceof SyntaxException syntax ? syntax : null;
    }

    /**
     * 把 Groovy 的英文编译报错转成中文说明，同时保留原始细节方便定位。
     * 格式：中文说明（原始英文细节）
     *
     * <p>**不带行号前缀**：行号由 SyntaxCheckResult.line 单独承载（技术方案 §5「错误(行号+原因)」
     * 把两者分开），前端用 errorLine 自己拼「第 N 行：」。若这里也拼一份，前端再拼就成了
     * 「第 2 行：第 2 行：…」。安全拦截路径本就是纯 message，去掉前缀后两条路径一致。
     *
     * <p>保留英文细节是有意的：Groovy 的原文里带着具体是哪个符号出的问题，
     * 全翻掉反而难查。但中文说明必须在前面，满足「错误信息可读」的要求。
     */
    static String humanizeCompileError(SyntaxException e) {
        String raw = e.getOriginalMessage() == null ? "" : e.getOriginalMessage().trim();
        String hint = hintOf(raw);
        StringBuilder sb = new StringBuilder(hint);
        if (!raw.isEmpty()) {
            sb.append("（").append(raw).append("）");
        }
        return sb.toString();
    }

    /** 常见 Groovy 编译错误的中文对照。命中不了就给通用说法，绝不返回英文类名 */
    private static String hintOf(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("unexpected end of file") || lower.contains("reached eof")) {
            return "脚本没有写完，括号或引号可能没闭合";
        }
        if (lower.contains("unexpected token")) {
            return "出现了不该有的符号";
        }
        if (lower.contains("unable to resolve class")) {
            return "用到了不存在的类";
        }
        if (lower.contains("cannot cast") || lower.contains("cannot assign")
                || lower.contains("incompatible") || lower.contains("cannot find matching")) {
            return "类型对不上";
        }
        if (lower.contains("expecting")) {
            return "这里还缺少内容";
        }
        if (lower.contains("duplicate") || lower.contains("already defined")) {
            return "有重复定义的名字";
        }
        if (lower.contains("illegal") || lower.contains("invalid")) {
            return "写法不合法";
        }
        return "语法错误";
    }

    /** 运行结果字符串的上限，防止脚本返回一个几 MB 的串把响应撑爆 */
    private static final int MAX_VALUE_LENGTH = 10_000;

    /** 错误信息的上限，够定位问题即可 */
    private static final int MAX_ERROR_LENGTH = 300;

    /**
     * 沙箱运行脚本。
     *
     * <p>流程：推断类型 → 把占位符替换成真实字面量 → 提交到独立线程池编译并执行 →
     * 限时等待。超时后 cancel(true) 中断，配合任务 10 注入的 @ThreadInterrupt 检查点，
     * 死循环才真的停得下来。
     *
     * <p>任何异常都在这一层收口，翻译成中文，绝不让堆栈或英文类名流到前端。
     */
    public RunResult run(String script, Map<String, String> params) {
        if (script == null || script.isBlank()) {
            return new RunResult(false, null, "脚本内容为空，请先写点东西再运行", false);
        }

        Map<String, String> types = PlaceholderTypeInferer.infer(script);
        String source;
        try {
            source = PlaceholderSubstitutor.toLiterals(script, params, types);
        } catch (PlaceholderValueException e) {
            return new RunResult(false, null, e.getMessage(), false);
        }

        Future<Object> future;
        try {
            future = scriptExecutor.submit(() -> {
                // 每次新建 GroovyShell，避免生成的脚本类都挂在同一个 ClassLoader 上导致 metaspace 泄漏
                return new GroovyShell(compilerConfiguration).evaluate(source);
            });
        } catch (RejectedExecutionException e) {
            return new RunResult(false, null, "当前运行的脚本过多，请稍后重试", false);
        }

        try {
            Object value = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new RunResult(true, stringify(value), null, false);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new RunResult(false, null,
                    "脚本执行超过 " + timeoutSeconds + " 秒，已自动中断。请检查是否有死循环或过大的计算量", true);
        } catch (ExecutionException e) {
            return new RunResult(false, null, translateRunError(e.getCause()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RunResult(false, null, "脚本执行被中断，请重试", false);
        }
    }

    /**
     * 把返回值转成字符串。null 统一给空串，前端据此显示「（无返回值）」。
     * 超长结果截断，避免一个脚本把响应体撑到几 MB。
     */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        try {
            text = String.valueOf(value);
        } catch (RuntimeException e) {
            // toString 自己抛异常的极端情况，不能让整个运行失败
            return "（返回值无法转成文本）";
        }
        if (text.length() > MAX_VALUE_LENGTH) {
            return text.substring(0, MAX_VALUE_LENGTH) + "…（结果过长，已截断）";
        }
        return text;
    }

    /**
     * 运行期异常翻译成可读中文。
     *
     * <p>两条硬要求：不能出现英文异常类名，不能出现堆栈。所以每种常见异常都有对照说法，
     * 认不出来的只取原因首行并剥掉「com.x.Y: 」这种类名前缀。
     */
    public static String translateRunError(Throwable cause) {
        Throwable t = unwrap(cause);
        if (t == null) {
            return "脚本执行出错，请检查脚本内容";
        }

        // 编译期问题（含安全拦截）复用语法校验那一套提取逻辑
        if (t instanceof MultipleCompilationErrorsException mce) {
            SyntaxCheckResult result = fromCompileErrors(mce);
            return result.message() == null ? "脚本无法编译，请检查写法后重试" : result.message();
        }
        if (t instanceof InterruptedException || t instanceof java.io.InterruptedIOException) {
            return "脚本被中断（可能因为执行超时）";
        }
        if (t instanceof StackOverflowError) {
            return "脚本递归太深或循环嵌套过多，已终止";
        }
        if (t instanceof OutOfMemoryError) {
            return "脚本占用的内存过大，已终止。请减少一次性构造的数据量";
        }
        if (t instanceof MissingPropertyException mpe) {
            return "用到了未定义的变量「" + mpe.getProperty() + "」";
        }
        if (t instanceof MissingMethodException mme) {
            return "调用了不存在的方法「" + mme.getMethod() + "」，请检查方法名和参数";
        }
        if (t instanceof ArithmeticException) {
            return "计算出错，可能是除以 0";
        }
        if (t instanceof NullPointerException) {
            return "脚本里出现了空值，对 null 取属性或调用方法了";
        }
        if (t instanceof ClassCastException) {
            return "类型转换失败，值的实际类型和期望的不一致";
        }
        if (t instanceof NumberFormatException) {
            return "字符串转数字失败，请检查填进去的值";
        }
        if (t instanceof IndexOutOfBoundsException) {
            return "下标越界，访问了不存在的位置";
        }
        if (t instanceof UnsupportedOperationException) {
            return "不支持的操作，可能是修改了只读的集合";
        }
        if (t instanceof SecurityException) {
            return ScriptSecurityException.MARKER + "操作被沙箱禁止";
        }

        String raw = t.getMessage();
        if (raw == null || raw.isBlank()) {
            return "脚本执行出错：" + simpleTypeName(t);
        }
        return truncate("脚本执行出错：" + stripClassPrefix(firstLine(raw)));
    }

    /** 剥掉 InvocationTargetException 这类包装，拿到真正的业务异常 */
    private static Throwable unwrap(Throwable cause) {
        Throwable t = cause;
        int guard = 0;
        while (t != null && guard++ < 10
                && (t instanceof java.lang.reflect.InvocationTargetException
                        || t instanceof java.lang.reflect.UndeclaredThrowableException
                        || t instanceof groovy.lang.GroovyRuntimeException && t.getCause() != null
                        && t.getMessage() == null)) {
            t = t.getCause();
        }
        return t;
    }

    /** 去掉消息里的「com.foo.Bar: 」类名前缀，Global Constraints 不允许英文类名出现在前端 */
    private static String stripClassPrefix(String raw) {
        String text = raw.trim();
        int colon = text.indexOf(':');
        if (colon > 0 && colon < 80) {
            String head = text.substring(0, colon);
            // 只有整段都是「点分隔的标识符」才认定是类名前缀，避免误删正常中文冒号前的内容
            if (head.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
                return text.substring(colon + 1).trim();
            }
        }
        return text;
    }

    /** 只取首行，Groovy 的异常消息常常后面跟着一大段候选列表 */
    private static String firstLine(String raw) {
        int nl = raw.indexOf('\n');
        return nl > 0 ? raw.substring(0, nl) : raw;
    }

    /** 认不出异常类型时给个中文说法，不直接暴露类名 */
    private static String simpleTypeName(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return "参数不合法";
        }
        if (t instanceof IllegalStateException) {
            return "状态不正确";
        }
        if (t instanceof groovy.lang.GroovyRuntimeException) {
            return "脚本运行出错";
        }
        return "脚本内部错误";
    }

    private static String truncate(String text) {
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) + "…" : text;
    }
}

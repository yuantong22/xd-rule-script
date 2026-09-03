package com.xd.rulescript.service;

import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.groovy.PlaceholderSubstitutor;
import com.xd.rulescript.groovy.PlaceholderTypeInferer;
import com.xd.rulescript.groovy.ScriptSecurityException;
import groovy.lang.GroovyShell;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.syntax.SyntaxException;
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
            ExecutorService scriptExecutor,
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
     * 格式：第 N 行：中文说明（原始英文细节）
     *
     * <p>保留英文细节是有意的：Groovy 的原文里带着具体是哪个符号出的问题，
     * 全翻掉反而难查。但中文说明必须在前面，满足「错误信息可读」的要求。
     */
    static String humanizeCompileError(SyntaxException e) {
        String raw = e.getOriginalMessage() == null ? "" : e.getOriginalMessage().trim();
        String hint = hintOf(raw);
        int line = e.getLine();
        StringBuilder sb = new StringBuilder();
        if (line > 0) {
            sb.append("第 ").append(line).append(" 行：");
        }
        sb.append(hint);
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

    /** 供任务 11 的 run 方法使用 */
    int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 供任务 11 的 run 方法使用 */
    ExecutorService scriptExecutor() {
        return scriptExecutor;
    }

    /** 供任务 11 的 run 方法使用 */
    CompilerConfiguration compilerConfiguration() {
        return compilerConfiguration;
    }
}

package com.xd.rulescript.config;

import com.xd.rulescript.groovy.ScriptSafetyCustomizer;
import groovy.lang.GroovyShell;
import java.io.File;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱编译配置。语法校验和运行共用这一份，保证「校验通过」和「运行不被拦」是一致的。
 *
 * <p>三层防护：
 * <ol>
 *   <li>SecureASTCustomizer —— Groovy 自带，禁 package、禁四种 import、接收者黑名单</li>
 *   <li>ScriptSafetyCustomizer —— 自研，按名字扫 AST，补上「先赋值给变量再调用」的盲区</li>
 *   <li>@ThreadInterrupt —— 往循环里注入中断检查点，让任务 11 的 future.cancel(true) 真能杀掉死循环。
 *       没有它，while(true) 是不响应 Thread.interrupt() 的，线程会一直空转泄漏</li>
 * </ol>
 */
@Configuration
public class GroovySandboxConfig {

    /**
     * 接收者黑名单里那些确定危险的类，用 Class 对象比字符串更精确。
     *
     * <p>SecureASTCustomizer#setDisallowedReceiversClasses 的形参是 raw 的 {@code List<Class>}，
     * 泛型不变性下 {@code List<Class<?>>} 传不进去，所以这里也用 raw 类型（靠赋值目标类型让
     * {@code List.of} 推断出 raw 的 Class 元素），并压掉随之而来的 rawtypes/unchecked 告警。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final List<Class> FORBIDDEN_RECEIVER_CLASSES = List.of(
            System.class,
            Runtime.class,
            ProcessBuilder.class,
            Thread.class,
            File.class,
            Socket.class,
            ServerSocket.class,
            URL.class,
            Method.class,
            ClassLoader.class,
            GroovyShell.class);

    /** 静态工厂，方便脱离 Spring 上下文做单测 */
    public static CompilerConfiguration build() {
        CompilerConfiguration config = new CompilerConfiguration();

        SecureASTCustomizer secure = new SecureASTCustomizer();
        // 禁 package 声明
        secure.setPackageAllowed(false);
        // 四种 import 白名单全部置空 = 一个都不许写。
        // 空列表在 Groovy 里表示「白名单已启用且不含任何项」，不是「不启用」
        secure.setAllowedImports(List.of());
        secure.setAllowedStarImports(List.of());
        secure.setAllowedStaticImports(List.of());
        secure.setAllowedStaticStarImports(List.of());
        // 不开间接 import 检查（保持默认 false）：它与上面「四个 import 白名单全置空」组合时，
        // 会把 java.lang.String 这类默认导入的类型也判成「未授权的间接 import」，
        // 导致任何方法调用（"abc".length()、list.collect {}）都被误杀。
        // 全限定名绕法（java.lang.System.exit）由 ScriptSafetyCustomizer 按名字兜住，不需要这一层。
        secure.setIndirectImportCheckEnabled(false);
        secure.setDisallowedReceiversClasses(FORBIDDEN_RECEIVER_CLASSES);

        config.addCompilationCustomizers(
                secure,
                new ScriptSafetyCustomizer(),
                new ASTTransformationCustomizer(groovy.transform.ThreadInterrupt.class));
        return config;
    }

    @Bean
    public CompilerConfiguration groovyCompilerConfiguration() {
        return build();
    }
}

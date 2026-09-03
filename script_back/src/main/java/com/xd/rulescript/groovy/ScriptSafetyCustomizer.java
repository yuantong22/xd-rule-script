package com.xd.rulescript.groovy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * 编译期危险能力拦截（自研，补 SecureASTCustomizer 的盲区）。
 *
 * <p>SecureASTCustomizer 只在接收者是静态可辨识的类表达式时生效，def s = System; s.exit(0)
 * 这种两跳写法它抓不到。本 customizer 在 CONVERSION 阶段按**名字**扫 AST，
 * MethodCallExpression / PropertyExpression / ConstructorCallExpression / ClassExpression /
 * VariableExpression 五种形态都查，能兜住第一跳。
 *
 * <p>代价是会误伤恰好命名为 File / Thread / System 的变量（首字母大写才算）。
 * 这是有意的取舍：规则脚本里这么命名极少见，而漏拦一次可能就把宿主进程干掉。
 *
 * <p>注意这只是静态黑名单，挡不住运行时拼类名再反射的刻意绕过。技术方案第 6 节已认账：
 * 单用户内部工具，静态黑名单 + 5 秒超时兜底，风险可接受。
 */
public class ScriptSafetyCustomizer extends CompilationCustomizer {

    /** 黑名单：简单类名 → 中文原因。用 LinkedHashMap 保证报错顺序稳定，测试才好断言 */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>();

    static {
        FORBIDDEN.put("System", "禁止访问宿主进程与环境变量");
        FORBIDDEN.put("Runtime", "禁止启动外部进程");
        FORBIDDEN.put("ProcessBuilder", "禁止启动外部进程");
        FORBIDDEN.put("Process", "禁止操作外部进程");
        FORBIDDEN.put("Thread", "禁止自行创建线程");
        FORBIDDEN.put("Runnable", "禁止自行创建线程");
        FORBIDDEN.put("Executor", "禁止自行创建线程池");
        FORBIDDEN.put("ExecutorService", "禁止自行创建线程池");
        FORBIDDEN.put("ClassLoader", "禁止动态加载类");
        FORBIDDEN.put("GroovyClassLoader", "禁止动态加载类");
        FORBIDDEN.put("GroovyShell", "禁止执行额外的脚本");
        FORBIDDEN.put("GroovyScriptEngine", "禁止执行额外的脚本");
        FORBIDDEN.put("File", "禁止访问文件");
        FORBIDDEN.put("Files", "禁止访问文件");
        FORBIDDEN.put("Paths", "禁止访问文件");
        FORBIDDEN.put("Path", "禁止访问文件");
        FORBIDDEN.put("FileInputStream", "禁止访问文件");
        FORBIDDEN.put("FileOutputStream", "禁止访问文件");
        FORBIDDEN.put("FileReader", "禁止访问文件");
        FORBIDDEN.put("FileWriter", "禁止访问文件");
        FORBIDDEN.put("RandomAccessFile", "禁止访问文件");
        FORBIDDEN.put("Socket", "禁止访问网络");
        FORBIDDEN.put("ServerSocket", "禁止访问网络");
        FORBIDDEN.put("URL", "禁止访问网络");
        FORBIDDEN.put("URI", "禁止访问网络");
        FORBIDDEN.put("URLConnection", "禁止访问网络");
        FORBIDDEN.put("HttpURLConnection", "禁止访问网络");
        FORBIDDEN.put("HttpClient", "禁止访问网络");
        FORBIDDEN.put("Method", "禁止用反射绕过沙箱");
        FORBIDDEN.put("Field", "禁止用反射绕过沙箱");
        FORBIDDEN.put("Constructor", "禁止用反射绕过沙箱");
        FORBIDDEN.put("Unsafe", "禁止用反射绕过沙箱");
        FORBIDDEN.put("ScriptEngine", "禁止执行额外的脚本");
        FORBIDDEN.put("ScriptEngineManager", "禁止执行额外的脚本");
    }

    /**
     * 反射 / 动态类加载的方法名黑名单。类名黑名单抓不到这些：
     * String.class.getMethod("x") 里根本没出现 "Method" 这个类名 token，只有方法名 getMethod。
     * 所以按方法名再拦一层，堵住反射绕过。
     */
    private static final Map<String, String> FORBIDDEN_METHODS = new LinkedHashMap<>();

    static {
        FORBIDDEN_METHODS.put("getMethod", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getMethods", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredMethod", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredMethods", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getField", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getFields", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredField", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredFields", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getConstructor", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getConstructors", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredConstructor", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredConstructors", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("invoke", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("setAccessible", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("forName", "禁止用反射动态加载类");
        FORBIDDEN_METHODS.put("getClassLoader", "禁止动态加载类");
        FORBIDDEN_METHODS.put("newInstance", "禁止用反射实例化类");
    }

    public ScriptSafetyCustomizer() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode)
            throws SecurityException {
        ErrorReporter reporter = new ErrorReporter(source);

        // 1) package 声明：脚本不该有包名，有就说明在试图伪装成工程代码
        ModuleNode module = source.getAST();
        if (module != null) {
            if (module.getPackage() != null) {
                reporter.report(module.getPackage(), "禁止在脚本里声明 package");
            }
            // 2) 四种 import 一律禁止：需要的能力都在默认导入里，要 import 的基本都是想干坏事
            module.getImports().forEach(i -> reporter.report(i, "禁止使用 import 语句"));
            module.getStarImports().forEach(i -> reporter.report(i, "禁止使用 import 语句"));
            module.getStaticImports().forEach((name, i) -> reporter.report(i, "禁止使用 import 语句"));
            module.getStaticStarImports().forEach((name, i) -> reporter.report(i, "禁止使用 import 语句"));
        }

        // 3) 遍历 AST 查黑名单名字
        SafetyVisitor visitor = new SafetyVisitor(source, reporter);
        visitor.visitClass(classNode);
    }

    /** 把违规项转成带行号的编译错误，走 Groovy 原生错误通道，前端能像语法错误一样标红 */
    private static final class ErrorReporter {

        private final SourceUnit source;

        private ErrorReporter(SourceUnit source) {
            this.source = source;
        }

        private void report(ASTNode node, String reason) {
            int line = node == null ? -1 : node.getLineNumber();
            int column = node == null ? -1 : node.getColumnNumber();
            String text = ScriptSecurityException.MARKER + reason;
            SyntaxException se = new SyntaxException(text, Math.max(line, 1), Math.max(column, 1));
            // addErrorAndContinue 会在超出容错阈值时立刻抛 MultipleCompilationErrorsException
            source.getErrorCollector().addErrorAndContinue(new SyntaxErrorMessage(se, source));
        }
    }

    /** 五种表达式形态都查，命中黑名单就报 */
    private static final class SafetyVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit source;
        private final ErrorReporter reporter;

        private SafetyVisitor(SourceUnit source, ErrorReporter reporter) {
            this.source = source;
            this.reporter = reporter;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return source;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            check(call.getObjectExpression());
            // 再按方法名拦一层反射：getMethod/invoke 这类没有类名 token，类名黑名单抓不到
            String methodName = call.getMethodAsString();
            if (methodName != null) {
                String reason = FORBIDDEN_METHODS.get(methodName);
                if (reason != null) {
                    reporter.report(call, "禁止调用 " + methodName + "（" + reason + "）");
                }
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            check(expression.getObjectExpression());
            super.visitPropertyExpression(expression);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            // isSpecialCall() 为 true 时是 this()/super()，不是真的在 new 东西
            if (!call.isSpecialCall()) {
                hit(call.getType().getNameWithoutPackage(), call);
            }
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitClassExpression(ClassExpression expression) {
            hit(expression.getType().getNameWithoutPackage(), expression);
            super.visitClassExpression(expression);
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            // 这一条是为了兜住 def s = System; s.exit(0)：此时 System 还是个变量表达式
            hit(expression.getName(), expression);
            super.visitVariableExpression(expression);
        }

        private void check(Expression expression) {
            if (expression instanceof ClassExpression classExpression) {
                hit(classExpression.getType().getNameWithoutPackage(), expression);
            } else if (expression instanceof VariableExpression variable) {
                hit(variable.getName(), expression);
            } else if (expression instanceof PropertyExpression property) {
                // java.lang.System 这种全限定写法，整段文本拿去比对
                hit(property.getText(), expression);
            }
        }

        /** 名字可能是简单名、全限定名或 a.b.C 形式，取最后一段比对，再整体比对一次 */
        private void hit(String name, ASTNode node) {
            if (name == null || name.isEmpty()) {
                return;
            }
            String reason = FORBIDDEN.get(name);
            if (reason == null) {
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1) {
                    reason = FORBIDDEN.get(name.substring(dot + 1));
                }
            }
            if (reason != null) {
                reporter.report(node, "禁止使用 " + simpleName(name) + "（" + reason + "）");
            }
        }

        private static String simpleName(String name) {
            int dot = name.lastIndexOf('.');
            return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
        }
    }

    /** 便于测试断言：暴露黑名单条目数，改动黑名单时能察觉 */
    static int forbiddenCount() {
        return FORBIDDEN.size();
    }

    /** 便于其它层复用黑名单名字（例如任务 11 的运行期兜底提示） */
    static List<String> forbiddenNames() {
        return List.copyOf(FORBIDDEN.keySet());
    }
}

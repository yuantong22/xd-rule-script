package com.xd.rulescript.groovy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

/**
 * 从变量声明语句推断 ${占位符} 的类型。
 *
 * <p>做法：把 ${age} 换成合法标识符 __ph_age（见 PlaceholderSubstitutor#toIdentifiers），
 * 解析到 CONVERSION 阶段拿到语法树，遍历所有声明语句，凡是右值为 __ph_ 开头变量的，
 * 就取左值的声明类型并归一为五种之一。
 *
 * <p>只解析不编译执行，所以脚本里有未定义变量也不会报错。解析失败时返回「全部按 String」，
 * 把报错的机会让给语法校验 —— 推断本来就跑在校验之前，此时脚本很可能还是坏的。
 */
public final class PlaceholderTypeInferer {

    /** 归一映射表，是 normalize 的唯一事实来源 */
    private static final Map<String, String> NORMALIZE_TABLE = Map.ofEntries(
            Map.entry("int", "int"), Map.entry("Integer", "int"),
            Map.entry("java.lang.Integer", "int"), Map.entry("short", "int"),
            Map.entry("Short", "int"), Map.entry("byte", "int"), Map.entry("Byte", "int"),
            Map.entry("long", "long"), Map.entry("Long", "long"),
            Map.entry("java.lang.Long", "long"), Map.entry("BigInteger", "long"),
            Map.entry("java.math.BigInteger", "long"),
            Map.entry("double", "double"), Map.entry("Double", "double"),
            Map.entry("java.lang.Double", "double"), Map.entry("float", "double"),
            Map.entry("Float", "double"), Map.entry("java.lang.Float", "double"),
            Map.entry("BigDecimal", "double"), Map.entry("java.math.BigDecimal", "double"),
            Map.entry("Number", "double"), Map.entry("java.lang.Number", "double"),
            Map.entry("boolean", "boolean"), Map.entry("Boolean", "boolean"),
            Map.entry("java.lang.Boolean", "boolean"),
            Map.entry("String", "String"), Map.entry("java.lang.String", "String"),
            Map.entry("CharSequence", "String"), Map.entry("java.lang.CharSequence", "String"),
            Map.entry("GString", "String"), Map.entry("char", "String"),
            Map.entry("Character", "String"), Map.entry("java.lang.Character", "String"));

    private static final String DEFAULT_TYPE = "String";

    private PlaceholderTypeInferer() {
    }

    /** 把 Groovy 声明类型名归一为 int/long/double/boolean/String，认不出的一律 String */
    public static String normalize(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return DEFAULT_TYPE;
        }
        String key = declaredType.trim();
        String hit = NORMALIZE_TABLE.get(key);
        if (hit != null) {
            return hit;
        }
        // 兜底再试一次简单类名，覆盖用户写完全限定名的情况
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            hit = NORMALIZE_TABLE.get(key.substring(dot + 1));
            if (hit != null) {
                return hit;
            }
        }
        return DEFAULT_TYPE;
    }

    /**
     * 推断原始脚本里每个占位符的类型。
     *
     * @param script 原始脚本（含 ${} 原样写法），可为 null
     * @return 键为占位符名、值为归一类型的有序 map；顺序与占位符在脚本中的出现顺序一致
     */
    public static Map<String, String> infer(String script) {
        List<String> names = PlaceholderSubstitutor.extractNames(script);
        Map<String, String> result = new LinkedHashMap<>();
        // 先全部按 String 占位，保证即使解析失败每个占位符也都有类型
        for (String name : names) {
            result.put(name, DEFAULT_TYPE);
        }
        if (names.isEmpty()) {
            return result;
        }
        try {
            collectFromAst(PlaceholderSubstitutor.toIdentifiers(script)).forEach((name, type) -> {
                if (result.containsKey(name)) {
                    result.put(name, type);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            // 语法错误、解析器内部异常都吞掉，保持「全部 String」的兜底结果
        }
        return result;
    }

    /** 解析到 CONVERSION 阶段，遍历所有类的声明语句，收集 __ph_ 变量的声明类型 */
    private static Map<String, String> collectFromAst(String scriptWithIdents) {
        CompilationUnit unit = new CompilationUnit(new CompilerConfiguration());
        SourceUnit sourceUnit = unit.addSource("PlaceholderInfer.groovy", scriptWithIdents);
        unit.compile(Phases.CONVERSION);

        CompileUnit compileUnit = unit.getAST();
        DeclarationVisitor visitor = new DeclarationVisitor(sourceUnit);
        // 必须遍历所有 module 的 getClasses()，否则方法体内的声明收不到。
        // getAST() 返回的是 CompileUnit（module 的容器），脚本编译后只有一个 module
        for (ModuleNode module : compileUnit.getModules()) {
            for (ClassNode classNode : module.getClasses()) {
                visitor.visitClass(classNode);
            }
        }
        return visitor.collected;
    }

    /** 只关心「声明语句的右值是个 __ph_ 变量」这一种形态 */
    private static final class DeclarationVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit sourceUnit;
        private final Map<String, String> collected = new LinkedHashMap<>();

        private DeclarationVisitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            Expression right = expression.getRightExpression();
            if (right instanceof VariableExpression variable) {
                String varName = variable.getName();
                if (varName != null && varName.startsWith(PlaceholderSubstitutor.IDENT_PREFIX)) {
                    String placeholder = varName.substring(PlaceholderSubstitutor.IDENT_PREFIX.length());
                    String declared = expression.getLeftExpression().getType().getName();
                    // 同一占位符被多处声明时以首个为准，不覆盖
                    collected.putIfAbsent(placeholder, normalize(declared));
                }
            }
            super.visitDeclarationExpression(expression);
        }
    }
}

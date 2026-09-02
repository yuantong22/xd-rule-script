package com.xd.rulescript.groovy;

import com.xd.rulescript.groovy.GroovyLexScanner.Region;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符 ${变量名} 的提取与替换。
 *
 * <p>替换必须区分「代码区」和「字符串区」：同一个 ${level}，写在 int age = ${age} 里要换成
 * 裸字面量，写在 "${level}" 里要换成裸文本（外面已经有引号了，再套一层就错了）。
 * 区域由 {@link GroovyLexScanner} 给出。
 *
 * <p>本类无状态、不依赖 Spring，可脱离上下文单测。
 */
public final class PlaceholderSubstitutor {

    /** 类型推断阶段把 ${age} 换成这个前缀加变量名，让它成为一个合法标识符 */
    public static final String IDENT_PREFIX = "__ph_";

    /** 占位符语法：${变量名}，变量名限定 \w+ */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)\\}");

    /** 类型只有这五种，推不出来一律按 String（与需求文档 4.3.2 一致） */
    private static final String T_INT = "int";
    private static final String T_LONG = "long";
    private static final String T_DOUBLE = "double";
    private static final String T_BOOLEAN = "boolean";
    private static final String T_STRING = "String";

    private static final Pattern INT_LITERAL = Pattern.compile("-?\\d+");
    private static final Pattern DOUBLE_LITERAL = Pattern.compile("-?(\\d+\\.\\d*|\\.\\d+|\\d+)");

    private PlaceholderSubstitutor() {
    }

    /** 按出现顺序去重返回全部占位符名 */
    public static List<String> extractNames(String script) {
        Set<String> names = new LinkedHashSet<>();
        if (script == null || script.isEmpty()) {
            return new ArrayList<>(names);
        }
        Matcher m = PLACEHOLDER.matcher(script);
        while (m.find()) {
            names.add(m.group(1));
        }
        return new ArrayList<>(names);
    }

    /**
     * 把每个 ${name} 换成 __ph_name，供类型推断阶段解析 AST 用。
     * 所有区域一视同仁：串内换成 __ph_name 后仍在引号里，是个合法的字符串字面量。
     */
    public static String toIdentifiers(String script) {
        return replace(script, (name, region) -> IDENT_PREFIX + name);
    }

    /** 语法校验用：按类型填假值，让脚本能被编译（不执行） */
    public static String toFakeValues(String script, Map<String, String> types) {
        return replace(script, (name, region) -> {
            String type = typeOf(types, name);
            return region == Region.CODE ? fakeCodeLiteral(type) : fakeRawToken(type);
        });
    }

    /**
     * 沙箱运行用：填真实值。
     *
     * @throws PlaceholderValueException 缺值，或值与声明类型对不上（消息为可读中文）
     */
    public static String toLiterals(String script, Map<String, String> params, Map<String, String> types) {
        return replace(script, (name, region) -> {
            String type = typeOf(types, name);
            if (params == null || !params.containsKey(name)) {
                throw new PlaceholderValueException("缺少占位符 " + name + " 的值，请先在下方填写");
            }
            String value = params.get(name) == null ? "" : params.get(name);
            String checked = checkAndNormalize(name, type, value);
            if (region == Region.CODE) {
                return codeLiteral(type, checked);
            }
            return region == Region.SINGLE_QUOTED
                    ? escapeSingleQuoted(checked)
                    : escapeDoubleQuoted(checked);
        });
    }

    // ================= 内部实现 =================

    /** 单个占位符的替换决策 */
    private interface Replacer {
        String apply(String name, Region region);
    }

    /** 区域数组与原始脚本等长且下标对齐，所以能直接用 ${ 的位置查区域 */
    private static String replace(String script, Replacer replacer) {
        if (script == null) {
            return "";
        }
        if (script.isEmpty()) {
            return script;
        }
        Region[] regions = GroovyLexScanner.scan(script);
        Matcher m = PLACEHOLDER.matcher(script);
        StringBuilder sb = new StringBuilder(script.length() + 32);
        int cursor = 0;
        while (m.find()) {
            sb.append(script, cursor, m.start());
            sb.append(replacer.apply(m.group(1), regions[m.start()]));
            cursor = m.end();
        }
        sb.append(script, cursor, script.length());
        return sb.toString();
    }

    private static String typeOf(Map<String, String> types, String name) {
        if (types == null) {
            return T_STRING;
        }
        String t = types.get(name);
        return t == null || t.isBlank() ? T_STRING : t;
    }

    private static String fakeCodeLiteral(String type) {
        return switch (type) {
            case T_INT -> "0";
            case T_LONG -> "0L";
            case T_DOUBLE -> "0.0d";
            case T_BOOLEAN -> "false";
            default -> "\"\"";
        };
    }

    /** 串内的假值：不加引号，String 直接给空文本 */
    private static String fakeRawToken(String type) {
        return switch (type) {
            case T_INT, T_LONG -> "0";
            case T_DOUBLE -> "0.0";
            case T_BOOLEAN -> "false";
            default -> "";
        };
    }

    /**
     * 校验填值是否符合声明类型，并返回可安全嵌入脚本的形式。
     * 错误消息一律中文、带上变量名和实际填入的值，方便用户自己改。
     */
    private static String checkAndNormalize(String name, String type, String value) {
        String v = value.trim();
        switch (type) {
            case T_INT, T_LONG -> {
                if (!INT_LITERAL.matcher(v).matches()) {
                    throw new PlaceholderValueException(
                            "占位符 " + name + " 需要整数，但填入的「" + value + "」不是合法整数");
                }
                return v;
            }
            case T_DOUBLE -> {
                if (!DOUBLE_LITERAL.matcher(v).matches()) {
                    throw new PlaceholderValueException(
                            "占位符 " + name + " 需要小数，但填入的「" + value + "」不是合法数字");
                }
                return v;
            }
            case T_BOOLEAN -> {
                if (!"true".equals(v) && !"false".equals(v)) {
                    throw new PlaceholderValueException(
                            "占位符 " + name + " 只能填 true 或 false，当前是「" + value + "」");
                }
                return v;
            }
            default -> {
                // String 不做格式校验，空串也合法；注意这里返回原值，不 trim
                return value;
            }
        }
    }

    /** 代码区的字面量：数字补后缀强制类型，字符串补引号并转义 */
    private static String codeLiteral(String type, String value) {
        return switch (type) {
            case T_LONG -> value + "L";
            case T_DOUBLE -> value + "d";
            case T_INT, T_BOOLEAN -> value;
            default -> "\"" + escapeDoubleQuoted(value) + "\"";
        };
    }

    /**
     * 双引号字符串语境的转义：反斜杠、双引号、换行/回车/制表，以及 $。
     * $ 必须转义，否则用户填的值里带 ${xxx} 会被 Groovy 当 GString 再插值一次。
     */
    private static String escapeDoubleQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '$' -> sb.append("\\$");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 单引号字符串语境的转义：只处理反斜杠、单引号和空白控制符。
     * 注意 $ 不转义 —— 单引号串不做插值，写 \$ 会让结果多出一个反斜杠。
     */
    private static String escapeSingleQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

package com.xd.rulescript.groovy;

import java.util.Arrays;

/**
 * 扫描 Groovy 脚本，标记每个字符所处的区域。
 *
 * <p>只区分三类：代码区、单引号字符串内、双引号字符串内。占位符替换需要按区域
 * 采用不同策略（代码区补引号、双引号串内不能补引号），因此这个扫描结果是替换逻辑的唯一依据。
 *
 * <p>故意从简的边界（都是安全方向）：
 * <ul>
 *   <li>注释区（// 与 /* *\/）归为 CODE：注释里的 ${x} 替换后仍在注释里，不影响编译</li>
 *   <li>不识别 slashy 字符串 /.../（Groovy 正则字面量），规则脚本用不到</li>
 *   <li>未闭合的字符串扫到行尾就停，不报错（语法校验会给出行号）</li>
 * </ul>
 */
public final class GroovyLexScanner {

    /** 字符所处区域 */
    public enum Region {
        /** 代码区（含注释区） */
        CODE,
        /** 单引号字符串内，'...' 或 '''...''' */
        SINGLE_QUOTED,
        /** 双引号字符串内，"..." 或 \"\"\"...\"\"\"；只有这里面的 ${x} 是 GString 插值 */
        DOUBLE_QUOTED
    }

    private GroovyLexScanner() {
    }

    /**
     * @param script 原始脚本（含 ${占位符}，此时还不是合法 Groovy）
     * @return 与 script 等长的区域数组
     */
    public static Region[] scan(String script) {
        if (script == null || script.isEmpty()) {
            return new Region[0];
        }
        int length = script.length();
        Region[] regions = new Region[length];
        Arrays.fill(regions, Region.CODE);

        int i = 0;
        while (i < length) {
            char c = script.charAt(i);

            // 行注释：扫到行尾，全程保持 CODE
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '/') {
                while (i < length && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // 块注释：扫到 */，全程保持 CODE
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                int end = i + 2;
                while (end + 1 < length && !(script.charAt(end) == '*' && script.charAt(end + 1) == '/')) {
                    end++;
                }
                i = Math.min(length, end + 2);
                continue;
            }

            // 三引号字符串（''' 或 \"\"\"）
            if ((c == '\'' || c == '"') && i + 2 < length
                    && script.charAt(i + 1) == c && script.charAt(i + 2) == c) {
                Region region = (c == '"') ? Region.DOUBLE_QUOTED : Region.SINGLE_QUOTED;
                int end = findTripleQuoteEnd(script, i + 3, c);
                Arrays.fill(regions, i, end, region);
                i = end;
                continue;
            }

            // 单行字符串
            if (c == '\'' || c == '"') {
                Region region = (c == '"') ? Region.DOUBLE_QUOTED : Region.SINGLE_QUOTED;
                int end = findQuoteEnd(script, i + 1, c);
                Arrays.fill(regions, i, end, region);
                i = end;
                continue;
            }

            i++;
        }
        return regions;
    }

    /** 找单行字符串的结束位置（返回值含结尾引号）；遇换行或末尾就停 */
    private static int findQuoteEnd(String script, int from, char quote) {
        int length = script.length();
        int j = from;
        while (j < length) {
            char c = script.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) {
                return Math.min(length, j + 1);
            }
            if (c == '\n') {
                return j;
            }
            j++;
        }
        return length;
    }

    /** 找三引号字符串的结束位置（返回值含结尾的三个引号） */
    private static int findTripleQuoteEnd(String script, int from, char quote) {
        int length = script.length();
        int j = from;
        while (j < length) {
            char c = script.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote && j + 2 < length && script.charAt(j + 1) == quote && script.charAt(j + 2) == quote) {
                return j + 3;
            }
            j++;
        }
        return length;
    }
}

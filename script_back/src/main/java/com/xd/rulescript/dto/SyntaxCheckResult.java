package com.xd.rulescript.dto;

/** 语法校验结果。ok=false 时 line 为出错行号（可能为 null，表示无法定位到具体行），message 为中文原因 */
public record SyntaxCheckResult(boolean ok, Integer line, String message) {

    /** 成功工厂命名为 pass() 而非 ok()：record 组件 ok 已生成同签名的读取器 ok()，静态方法不能再叫 ok() */
    public static SyntaxCheckResult pass() {
        return new SyntaxCheckResult(true, null, null);
    }

    public static SyntaxCheckResult error(Integer line, String message) {
        return new SyntaxCheckResult(false, line, message);
    }
}

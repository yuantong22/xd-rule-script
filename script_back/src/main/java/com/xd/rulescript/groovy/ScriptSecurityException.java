package com.xd.rulescript.groovy;

/**
 * 脚本触碰沙箱黑名单时抛出，消息即给前端看的中文提示。
 *
 * <p>所有安全类消息统一带 {@link #MARKER} 前缀，这样 GroovyEngineService 能把它和普通语法错误
 * 区分开：语法错误提示「哪一行写错了」，安全拦截提示「这个操作被禁止」。
 */
public class ScriptSecurityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 安全拦截消息的统一前缀，也是识别标记 */
    public static final String MARKER = "【安全拦截】";

    public ScriptSecurityException(String message) {
        super(message == null || message.startsWith(MARKER) ? message : MARKER + message);
    }

    /** 判断一段错误消息是不是安全拦截产生的 */
    public static boolean isSecurityMessage(String message) {
        return message != null && message.startsWith(MARKER);
    }

    /** 去掉前缀，用于把安全提示拼进更长的句子里 */
    public static String stripMarker(String message) {
        if (message == null) {
            return "";
        }
        return message.startsWith(MARKER) ? message.substring(MARKER.length()) : message;
    }
}

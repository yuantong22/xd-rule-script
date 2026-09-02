package com.xd.rulescript.common;

/**
 * 业务异常。message 必须是可直接展示给用户的中文提示。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(1000, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

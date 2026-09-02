package com.xd.rulescript.common;

/**
 * 统一响应包装。code=0 表示成功，其余为业务/系统错误码。
 * 业务失败也走 HTTP 200，前端只根据 code 判断。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}

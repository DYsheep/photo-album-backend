package com.photoalbum.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体，格式: { code, data, message }
 * 与前端 request.js 拦截器对齐
 */
@Data
public class Result<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    public Result() {}

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 成功 ==========
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static Result<Void> ok() {
        return new Result<>(200, "操作成功", null);
    }

    // ========== 失败 ==========
    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ========== 常用状态码快捷方法 ==========
    public static <T> Result<T> unauthorized() {
        return fail(401, "未授权，请重新登录");
    }

    public static <T> Result<T> forbidden() {
        return fail(403, "拒绝访问");
    }
}

package com.photoalbum.common;

import lombok.Getter;

/**
 * 自定义业务异常
 * 用于 Service 层抛出业务逻辑错误，由 GlobalExceptionHandler 统一捕获返回
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}

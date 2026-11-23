package com.photoalbum.common;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 * 统一返回 Result 格式，与前端拦截器对齐
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验异常（@Valid 触发）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        return Result.fail(400, fieldError.getField() + ": " + fieldError.getDefaultMessage());
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        return Result.fail(400, fieldError.getField() + ": " + fieldError.getDefaultMessage());
    }

    /**
     * 权限不足（方法级 @PreAuthorize 拒绝时抛出）
     * 统一转换为业务响应体，避免前端拿到 Spring Security 的默认错误页
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public Result<Void> handleAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        log.warn("访问被拒绝: {}", e.getMessage());
        return Result.fail(403, "无权限执行该操作");
    }

    /**
     * 未认证（无登录态时方法级鉴权抛出）
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public Result<Void> handleAuthenticationException(org.springframework.security.core.AuthenticationException e) {
        log.debug("未认证访问: {}", e.getMessage());
        return Result.fail(401, "未登录");
    }

    /**
     * 业务异常（自定义 BusinessException）
     *
     * 这里包含大量"被拒绝的写操作"：上传格式不符、文件超大、触发限流等。
     * 此前只返回、不落日志，导致事后无法回答"这一批为什么失败、失败了多少"。
     * 写操作记 WARN（量小且值得追），读路径压到 DEBUG（404 很常见，避免刷日志）。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e,
                                       jakarta.servlet.http.HttpServletRequest request) {
        String method = request == null ? "" : request.getMethod();
        String uri = request == null ? "" : request.getRequestURI();
        boolean writeOperation = "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
        if (writeOperation) {
            log.warn("业务异常 {} {} -> {}: {}（账号：{}）", method, uri, e.getCode(), e.getMessage(),
                    currentUsername());
        } else {
            log.debug("业务异常 {} {} -> {}: {}", method, uri, e.getCode(), e.getMessage());
        }
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 当前账号（未登录为 anonymous），仅用于日志 */
    private String currentUsername() {
        com.photoalbum.entity.User user =
                com.photoalbum.security.CurrentUserSupport.getCurrentUser();
        return user != null && user.getUsername() != null ? user.getUsername() : "anonymous";
    }

    /**
     * MyBatis-Plus 异常
     */
    @ExceptionHandler(MybatisPlusException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleMyBatisPlus(MybatisPlusException e) {
        log.error("数据库操作异常", e);
        return Result.fail("数据库操作失败");
    }

    /**
     * 其他未捕获异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleGeneric(Exception e) {
        log.error("系统内部错误", e);
        return Result.fail("服务器内部错误");
    }
}

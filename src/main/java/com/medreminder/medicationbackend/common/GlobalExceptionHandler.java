package com.medreminder.medicationbackend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 —— 让所有异常都走统一返回壳 {code, message, data}。
 *
 * 为什么需要它：
 * 没有它时，@Valid 校验失败会吐 Spring Boot 默认错误页（timestamp/status/error/path），
 * 前端就得为「业务错误」和「校验错误」写两套解析逻辑。有了它，全部收敛成 code+message。
 *
 * 注意：这里只改变「响应体」不改变 HTTP 状态码（一律 200），
 * 与项目既有风格一致 —— 前端只看 body 里的 code 判断成败。
 * 拦截器写的 401 不经过 Controller，不受这里影响。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** @Valid 参数校验失败：取第一条错误信息返回，前端提示更精准 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验不通过");
        return Result.error(Result.BAD_REQUEST, message);
    }

    /** 请求体不是合法 JSON / 类型不匹配（如日期格式错） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.error(Result.BAD_REQUEST, "请求体格式不正确");
    }

    /** 业务层主动抛出的参数错误（如「药品不存在或无权使用」） */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(Result.BAD_REQUEST, e.getMessage());
    }

    /** 唯一键冲突（账号重复注册等） */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        return Result.error(Result.BAD_REQUEST, "数据已存在，请勿重复提交");
    }

    /** 兜底：未预期的异常，打日志便于排查，对外只回笼统提示，不泄露堆栈 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("[未捕获异常]", e);
        return Result.error(500, "服务器内部错误，请稍后再试");
    }
}

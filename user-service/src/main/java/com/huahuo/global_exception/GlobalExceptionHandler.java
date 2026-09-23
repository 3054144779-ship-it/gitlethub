package com.huahuo.global_exception;

import common.Result;
import common.ResultCode;
import exception.LoginException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

//    @ExceptionHandler(RuntimeException.class)
//    public Result<?> handleRuntimeException(RuntimeException e) {
//        return Result.fail(ResultCode.INTERNAL_ERROR, e.getMessage());
//    }


    @ExceptionHandler(LoginException.class)
    public Result<?> handleLoginException(LoginException e) {
        return Result.fail(ResultCode.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        return Result.fail(ResultCode.INTERNAL_ERROR, "服务器内部错误");
    }
}

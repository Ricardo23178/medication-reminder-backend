package com.medreminder.medicationbackend.common;

import lombok.Data;

/**
 * 统一返回结构。全项目所有接口都套这个壳：
 *   { "code": 200, "message": "ok", "data": {...} }
 * 前端只需判断 code，不用为每个接口写不同的错误解析。
 */
@Data
public class Result<T> {

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = SUCCESS;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}

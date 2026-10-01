package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体。前端 POST 过来的 JSON：
 *   { "account": "zhangsan", "password": "123456", "confirmPassword": "123456",
 *     "nickname": "张三", "phone": "13800138000" }
 *
 * 校验规则（@Valid 触发，不通过由 GlobalExceptionHandler 统一转成 {code:400,message}）：
 * - account     ：必填，4-32 位字母/数字/下划线。限制字符集是为了避免登录时中文/空格歧义。
 * - password    ：必填，6-32 位。
 * - confirmPassword：必填；与 password 是否一致放 Service 里比对（跨字段校验注解写起来啰嗦）。
 * - nickname    ：必填，最长 64。
 * - phone       ：可选（null 放行）；一旦填写就必须是合法 11 位手机号。
 *                 注意用 ^$ 显式拒绝空串，避免前端误传 "" 被当成"已填写合法值"落库。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,32}$",
             message = "账号只能包含字母、数字、下划线，长度 4-32 位")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需为 6-32 位")
    private String password;

    @NotBlank(message = "请再次输入密码")
    private String confirmPassword;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    /** 可选字段：null 放行（不填），空串和非法值都拒绝 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}

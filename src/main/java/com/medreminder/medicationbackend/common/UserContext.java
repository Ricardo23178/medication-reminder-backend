package com.medreminder.medicationbackend.common;

/**
 * 当前登录用户上下文。
 * 拦截器验过 token 后，把用户身份放进 ThreadLocal；
 * Controller / Service 里随时 UserContext.userId() 就能拿到「是谁在操作」。
 * ThreadLocal = 每个请求线程一份独立副本，互不串号。
 */
public class UserContext {

    /** 当前登录用户快照 */
    public record CurrentUser(Long id, String role) {}

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.id();
    }

    public static String role() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.role();
    }

    /** 请求结束必须清理，防止线程池复用导致串号 */
    public static void clear() {
        HOLDER.remove();
    }
}

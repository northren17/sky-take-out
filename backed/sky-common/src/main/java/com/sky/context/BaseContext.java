package com.sky.context;

public class BaseContext {

    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();
    private static final ThreadLocal<String> userSessionId = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    public static void setCurrentId(Long id, String sessionId) {
        threadLocal.set(id);
        userSessionId.set(sessionId);
    }

    public static Long getCurrentId() {
        return threadLocal.get();
    }

    public static String getCurrentSessionId() {
        return userSessionId.get();
    }

    public static void removeCurrentId() {
        threadLocal.remove();
        userSessionId.remove();
    }

}

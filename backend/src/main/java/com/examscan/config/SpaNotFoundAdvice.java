package com.examscan.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 静态资源 404 处理：非接口、非文件、无扩展名的路径视为前端路由，
 * 转发到 index.html 由 vue-router 接管（如 /papers/9 刷新）。
 * Order(0) 确保优先于 GlobalExceptionHandler 的 Exception 兜底。
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaNotFoundAdvice {

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handle(HttpServletRequest request, NoResourceFoundException e) {
        String path = request.getRequestURI();
        boolean spaPath = !path.startsWith("/api/") && !path.startsWith("/files/")
                && !path.startsWith("/h2-console") && !path.contains(".");
        if (spaPath) {
            return "forward:/index.html";
        }
        return ResponseEntity.status(404).body("Not Found: " + path);
    }
}

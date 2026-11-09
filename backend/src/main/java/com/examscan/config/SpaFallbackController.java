package com.examscan.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPA 前端路由回退：404 且非接口/资源路径时转发到 index.html，
 * 由前端路由接管（如 /papers/7 直接刷新仍可访问）。
 */
@Controller
public class SpaFallbackController implements ErrorController {

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request) {
        Object statusObj = request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE);
        Object uriObj = request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI);
        int status = statusObj != null ? Integer.parseInt(statusObj.toString()) : 500;
        String uri = uriObj != null ? uriObj.toString() : "";

        boolean spaPath = !uri.startsWith("/api/") && !uri.startsWith("/files/")
                && !uri.startsWith("/h2-console") && !uri.contains(".");

        if (status == HttpStatus.NOT_FOUND.value() && spaPath) {
            return "forward:/index.html";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", "请求失败: " + uri);
        return ResponseEntity.status(status).body(body);
    }
}

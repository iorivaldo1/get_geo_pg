package com.qskj.get_geo_pg.interceptor;

import com.qskj.get_geo_pg.service.JwtService;
import com.qskj.get_geo_pg.service.RsaKeyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RsaKeyService rsaKeyService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS 请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                // 验证 token 签名及过期时间
                Map<String, Object> payload = jwtService.decodeAndVerify(token, rsaKeyService.getPublicKey());
                
                // 模块隔离校验 (aud)
                String path = request.getRequestURI();
                String contextPath = request.getContextPath();
                if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                    path = path.substring(contextPath.length());
                }

                Object audObj = payload.get("aud");
                String aud = audObj != null ? audObj.toString() : "";

                boolean allowed = true;
                if (path.startsWith("/excalidraw")) {
                    allowed = "excalidraw".equals(aud);
                } else if (path.startsWith("/fungis_user")) {
                    allowed = "fungis".equals(aud);
                } else if (path.startsWith("/hhgl_user")) {
                    allowed = "hhgl".equals(aud);
                } else if (path.startsWith("/geo")) {
                    // /geo/** 允许 hhgl 和 fungis 访问
                    allowed = "hhgl".equals(aud) || "fungis".equals(aud);
                }

                if (!allowed) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401, \"msg\":\"Module token access denied: aud '" + aud + "' is not permitted to access " + path + "\"}");
                    return false;
                }

                // 将用户信息及模块存入 request 中供后续使用
                request.setAttribute("username", payload.get("sub"));
                request.setAttribute("role", payload.get("role"));
                request.setAttribute("aud", aud);
                
                return true;
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401, \"msg\":\"Token invalid or expired: " + e.getMessage() + "\"}");
                return false;
            }
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401, \"msg\":\"Missing Authorization header\"}");
        return false;
    }
}

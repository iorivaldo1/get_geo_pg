package com.qskj.get_geo_pg.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qskj.get_geo_pg.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tianditu")
@CrossOrigin
public class TiandituController {

    private static final String WEBGIS_TOKEN1 = "73a87062ca36baaed0feebe7989f453a";
    private static final String WEBGIS_TOKEN2 = "ce4546b64fa98c94f8fbc75a4f897919";
    private static final String WEBGIS_TOKEN3 = "251fde23a9628cd71799ed209e7292ab";
    private static final String WEBGIS_TOKEN4 = "439681263a168a3cb87a19c93b209ecb";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private com.qskj.get_geo_pg.service.JwtService jwtService;

    @Autowired
    private com.qskj.get_geo_pg.service.RsaKeyService rsaKeyService;

    /**
     * 获取配置文件的位置（优先存放在项目根目录 config/tianditu.json）
     */
    private File getConfigFile() {
        File configDir = new File("config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "tianditu.json");
    }

    /**
     * 读取配置文件中的 Token 数据
     */
    private Map<String, Object> readConfig() {
        File configFile = getConfigFile();
        if (configFile.exists()) {
            try {
                return objectMapper.readValue(configFile, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 默认初始化配置
        Map<String, Object> defaultMap = new HashMap<>();
        defaultMap.put("token", WEBGIS_TOKEN1);
        defaultMap.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        writeConfig(defaultMap);
        return defaultMap;
    }

    /**
     * 写入数据到配置文件
     */
    private boolean writeConfig(Map<String, Object> data) {
        try {
            File configFile = getConfigFile();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, data);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * GET /get_geo_pg/api/tianditu/token
     * 读取配置文件中存的天地图 Token
     */
    @GetMapping("/token")
    public Result<Map<String, Object>> getToken() {
        try {
            Map<String, Object> config = readConfig();
            return Result.ok(config);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("获取天地图 Token 失败: " + e.getMessage());
        }
    }

    /**
     * POST /get_geo_pg/api/tianditu/token
     * 切换天地图 Token (角色鉴权：需要 admin 权限)
     */
    @PostMapping("/token")
    public Result<Map<String, Object>> updateToken(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        try {
            if (body == null) {
                body = new HashMap<>();
            }

            boolean isAdmin = false;

            // 优先通过 RSA 私钥签名的 JWT Token 校验用户真实身份与角色
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String tokenStr = authHeader.substring(7);
                try {
                    Map<String, Object> payload = jwtService.decodeAndVerify(tokenStr, rsaKeyService.getPublicKey());
                    Object authObj = payload.get("auth");
                    Object roleObj = payload.get("role");
                    String authStr = String.valueOf(authObj);
                    String roleStr = String.valueOf(roleObj);
                    if ("serverAdmin".equalsIgnoreCase(authStr) || "serverAdmin".equalsIgnoreCase(roleStr) || "admin".equalsIgnoreCase(authStr) || "admin".equalsIgnoreCase(roleStr)) {
                        isAdmin = true;
                    }
                } catch (Exception e) {
                    System.out.println("JWT 解密验签失败: " + e.getMessage());
                }
            }

            // 开发/本地测试后备：支持 request 中的解密属性
            if (!isAdmin) {
                Object reqAuth = request.getAttribute("authority");
                Object reqRole = request.getAttribute("role");
                String reqAuthStr = String.valueOf(reqAuth);
                String reqRoleStr = String.valueOf(reqRole);
                if ("serverAdmin".equalsIgnoreCase(reqAuthStr) || "serverAdmin".equalsIgnoreCase(reqRoleStr) || "admin".equalsIgnoreCase(reqAuthStr) || "admin".equalsIgnoreCase(reqRoleStr)) {
                    isAdmin = true;
                }
            }

            if (!isAdmin) {
                return new Result<>(403, "权限拒绝：修改天地图 Token 需要后台管理员(serverAdmin)权限", null);
            }

            // 校验新 Token
            Object tokenObj = body.get("token");
            if (tokenObj == null || tokenObj.toString().trim().isEmpty()) {
                return Result.fail("Token 密钥不能为空");
            }

            String newToken = tokenObj.toString().trim();
            Map<String, Object> newConfig = new HashMap<>();
            newConfig.put("token", newToken);
            newConfig.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            if (writeConfig(newConfig)) {
                return Result.ok(newConfig);
            } else {
                return Result.fail("配置文件写入失败");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("修改 Token 异常: " + e.getMessage());
        }
    }
}

package com.qskj.get_geo_pg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 业务服务
 *
 * 负责：
 * - 生成 RS256 签名的 JWT Token
 * - 验证 JWT 签名并解析 Payload
 */
@Service
public class JwtService {

    private static final JsonParser JSON_PARSER = JsonParserFactory.getJsonParser();

    @Value("${jwt.issuer:get-geo-pg-auth}")
    private String issuer;

    @Value("${jwt.key-id:geo_pg_rsa_key}")
    private String keyId;

    @Autowired
    private RsaKeyService rsaKeyService;

    public String generateToken(String username, String auth, String role) throws Exception {
        return generateToken(username, auth, role, "default");
    }

    public String generateToken(String username, String auth, String role, String app) throws Exception {
        long now = System.currentTimeMillis() / 1000L;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "RS256");
        header.put("kid", keyId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", username);
        if (app != null && !app.isEmpty()) {
            payload.put("aud", app);
        }
        payload.put("auth", auth);
        payload.put("role", role);
        payload.put("iat", now);
        payload.put("exp", now + 3600L);

        String headerB64 = base64UrlEncode(toJson(header).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64UrlEncode(toJson(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(rsaKeyService.getPrivateKey());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        return signingInput + "." + base64UrlEncode(signature);
    }

    public Map<String, Object> decodeAndVerify(String jwt, PublicKey publicKey) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] signature = base64UrlDecode(parts[2]);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(signature)) {
            throw new SecurityException("Signature verification failed");
        }

        byte[] payloadBytes = base64UrlDecode(parts[1]);
        Map<String, Object> payload = JSON_PARSER.parseMap(
                new String(payloadBytes, StandardCharsets.UTF_8));

        long now = System.currentTimeMillis() / 1000L;
        Object exp = payload.get("exp");
        if (exp instanceof Number && ((Number) exp).longValue() < now) {
            throw new SecurityException("Token expired");
        }
        Object nbf = payload.get("nbf");
        if (nbf instanceof Number && ((Number) nbf).longValue() > now) {
            throw new SecurityException("Token not active yet");
        }
        return payload;
    }

    public static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] base64UrlDecode(String data) {
        String s = data.replace('-', '+').replace('_', '/');
        switch (s.length() % 4) {
            case 2:
                s += "==";
                break;
            case 3:
                s += "=";
                break;
            default:
                break;
        }
        return Base64.getDecoder().decode(s);
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null)
            return "null";
        if (value instanceof Number || value instanceof Boolean)
            return value.toString();
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first)
                    sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(e.getKey())).append("\":").append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }
}

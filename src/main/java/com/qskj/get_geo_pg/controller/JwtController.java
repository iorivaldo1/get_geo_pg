package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.service.JwtService;
import com.qskj.get_geo_pg.service.RsaKeyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/jwt")
@CrossOrigin
public class JwtController {

    @Autowired
    private RsaKeyService rsaKeyService;

    @Autowired
    private JwtService jwtService;

    @GetMapping("/validateAuthKey")
    public void validateAuthKey(@RequestParam(value = "key", required = false) String key,
                                HttpServletRequest request,
                                HttpServletResponse response) throws Exception {
        response.setContentType("text/plain;charset=UTF-8");

        if (key == null || key.isEmpty()) {
            response.setStatus(401);
            response.getWriter().write("Error: key is empty");
            return;
        }

        try {
            byte[] headerBytes = JwtService.base64UrlDecode(key.split("\\.")[0]);
            Map<String, Object> headerMap = org.springframework.boot.json.JsonParserFactory
                    .getJsonParser()
                    .parseMap(new String(headerBytes, StandardCharsets.UTF_8));
            String kid = headerMap.get("kid") == null ? "" : String.valueOf(headerMap.get("kid"));

            PublicKey publicKey;
            if ("test_key".equals(kid)) {
                publicKey = rsaKeyService.getPublicKey();
            } else {
                throw new IllegalArgumentException("Unsupported kid: " + kid);
            }

            Map<String, Object> payload = jwtService.decodeAndVerify(key, publicKey);

            Object auth = payload.get("auth");
            if (auth == null) {
                throw new IllegalArgumentException("auth missing in payload");
            }
            response.setStatus(200);
            response.getWriter().write(String.valueOf(auth));

        } catch (Exception e) {
            response.setStatus(401);
            response.getWriter().write("Unauthorized: " + e.getMessage());
        }
    }

    @GetMapping(value = "/getPublicKey", produces = MediaType.TEXT_PLAIN_VALUE)
    @CrossOrigin
    public String getPublicKey(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        try {
            byte[] encoded = rsaKeyService.getPublicKey().getEncoded();
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded);
            return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}

package com.qskj.get_geo_pg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 密钥服务
 *
 * 负责从 PEM 文件加载并缓存密钥对，提供：
 * - 获取私钥 / 公钥
 * - RSA 解密（用于前端加密密码的还原）
 */
@Service
public class RsaKeyService {

    @Value("${pem.dir}")
    private String pemDir;

    private PrivateKey privateKey;
    private PublicKey  publicKey;

    @PostConstruct
    public void init() throws Exception {
        String privPem = new String(
                Files.readAllBytes(Paths.get(pemDir, "private_key.pem")),
                StandardCharsets.US_ASCII);
        this.privateKey = loadPrivateKey(privPem);

        String pubPem = new String(
                Files.readAllBytes(Paths.get(pemDir, "public_key.pem")),
                StandardCharsets.US_ASCII);
        this.publicKey = loadPublicKey(pubPem);
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public String decryptWithPrivateKey(String encryptedBase64) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedBase64);
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    private static PrivateKey loadPrivateKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static PublicKey loadPublicKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}

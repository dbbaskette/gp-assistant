package com.baskettecase.gpassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting and decrypting sensitive data using AES-256-GCM.
 * Used to secure API keys in the database.
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12; // bytes
    private static final int KEY_SIZE = 256; // bits

    private final SecretKey secretKey;

    public EncryptionService(
            @Value("${app.security.encryption-key:}") String encryptionKeyBase64) {

        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            log.warn("⚠️  No encryption key configured! Generating temporary key...");
            log.warn("⚠️  Set APP_SECURITY_ENCRYPTION_KEY environment variable for production");
            this.secretKey = generateKey();
        } else {
            try {
                byte[] decodedKey = Base64.getDecoder().decode(encryptionKeyBase64);
                this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
                log.info("✅ Encryption service initialized with provided key");
            } catch (IllegalArgumentException e) {
                log.error("❌ Invalid encryption key format - must be Base64 encoded");
                throw new RuntimeException("Invalid encryption key", e);
            }
        }
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     *
     * @param plaintext Text to encrypt
     * @return Base64-encoded ciphertext with IV prepended
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }

        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine IV + ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            // Encode to Base64
            return Base64.getEncoder().encodeToString(byteBuffer.array());

        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypt ciphertext using AES-256-GCM.
     *
     * @param ciphertextBase64 Base64-encoded ciphertext with IV prepended
     * @return Decrypted plaintext
     */
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null || ciphertextBase64.isEmpty()) {
            return "";
        }

        try {
            // Decode from Base64
            byte[] ciphertextWithIv = Base64.getDecoder().decode(ciphertextBase64);

            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(ciphertextWithIv);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Decrypt
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Failed to decrypt data", e);
        }
    }

    /**
     * Generate a random AES-256 key for development/testing.
     * In production, use a persistent key from environment variable.
     */
    private SecretKey generateKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(KEY_SIZE, new SecureRandom());
            SecretKey key = keyGenerator.generateKey();

            log.info("Generated temporary encryption key: {}",
                    Base64.getEncoder().encodeToString(key.getEncoded()));
            log.warn("⚠️  This key will be lost on restart! Set APP_SECURITY_ENCRYPTION_KEY for persistence");

            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }

    /**
     * Generate a new random key and return it as Base64.
     * Utility method for initial setup.
     */
    public static String generateKeyBase64() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(KEY_SIZE, new SecureRandom());
            SecretKey key = keyGenerator.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }

    /**
     * Main method to generate a new encryption key for configuration.
     * Run: java -cp ... com.baskettecase.gpassistant.service.EncryptionService
     */
    public static void main(String[] args) {
        String key = generateKeyBase64();
        System.out.println("=".repeat(80));
        System.out.println("Generated AES-256 Encryption Key (Base64):");
        System.out.println("=".repeat(80));
        System.out.println(key);
        System.out.println("=".repeat(80));
        System.out.println("\nAdd to your .env file:");
        System.out.println("APP_SECURITY_ENCRYPTION_KEY=" + key);
        System.out.println("\nOr to application.yaml:");
        System.out.println("app:");
        System.out.println("  security:");
        System.out.println("    encryption-key: " + key);
        System.out.println("=".repeat(80));
    }
}

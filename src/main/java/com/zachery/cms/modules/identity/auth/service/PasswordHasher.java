package com.zachery.cms.modules.identity.auth.service;

import org.springframework.stereotype.Component;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;

/** Implements the fixed B-01 seed format; malformed or unknown formats fail closed. */
@Component
public class PasswordHasher {
    private static final String PREFIX = "{pbkdf2-sha256}600000$";
    private static final int ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();

    public String encode(String password) {
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new IllegalArgumentException("Invalid password length");
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] digest = derive(password, salt);
        try {
            return PREFIX + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(digest);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public boolean matches(String password, String encoded) {
        if (password == null || password.length() > 128 || encoded == null
                || encoded.length() > 255 || !encoded.startsWith(PREFIX)) return false;
        String[] parts = encoded.substring(PREFIX.length()).split("\\$", -1);
        if (parts.length != 2) return false;
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[0]);
            expected = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (salt.length != 16 || expected.length != 32) return false;
        byte[] actual = derive(password, salt);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private byte[] derive(String password, byte[] salt) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}

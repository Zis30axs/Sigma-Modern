package com.mentalfrostbyte.jello.music.netease;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * NetEase Cloud Music's request encryption, ported from the SigmaClient implementation.
 *
 * <ul>
 *   <li><b>weapi</b> (the web player): AES-128-CBC twice - with a fixed key, then a random one - and the random
 *       key reversed and RSA-encrypted with the web client's public key.</li>
 *   <li><b>eapi</b> (the desktop client): {@code url-36cd479b6b5-json-36cd479b6b5-md5(...)} under AES-128-ECB
 *       with the client's fixed key, upper-case hex. Responses may come back encrypted the same way.</li>
 * </ul>
 */
public final class NeteaseCrypto {
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final BigInteger PUBLIC_EXPONENT = new BigInteger("010001", 16);
    private static final BigInteger MODULUS = new BigInteger(
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7"
            + "b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280"
            + "104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932"
            + "575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b"
            + "3ece0462db0a22b8e7", 16);
    private static final String KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final String EAPI_KEY = "e82ckenh8dichen8";
    static final String EAPI_SEPARATOR = "-36cd479b6b5-";
    private static final SecureRandom RANDOM = new SecureRandom();

    private NeteaseCrypto() {}

    /** weapi: returns {@code {params, encSecKey}} for a JSON body. */
    public static String[] weapi(String json) {
        try {
            String secretKey = randomKey(16);
            String params = aesCbc(aesCbc(json, PRESET_KEY), secretKey);
            return new String[]{params, rsa(secretKey)};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("NetEase weapi encryption failed", e);
        }
    }

    private static String aesCbc(String text, String key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
            new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The web client's textbook RSA: the reversed key as a big-endian number, no padding, 256 hex digits. */
    private static String rsa(String key) {
        byte[] reversed = new StringBuilder(key).reverse().toString().getBytes(StandardCharsets.UTF_8);
        String hex = new BigInteger(1, reversed).modPow(PUBLIC_EXPONENT, MODULUS).toString(16);
        return "0".repeat(Math.max(0, 256 - hex.length())) + hex;
    }

    private static String randomKey(int length) {
        StringBuilder key = new StringBuilder(length);
        for (int i = 0; i < length; i++) key.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
        return key.toString();
    }

    /** eapi: the upper-case hex {@code params} for {@code path} (e.g. {@code /api/song/lyric/v1}) and a JSON body. */
    public static String eapi(String path, String json) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(("nobody" + path + "use" + json + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
            String plain = path + EAPI_SEPARATOR + json + EAPI_SEPARATOR + digest;
            return HexFormat.of().withUpperCase().formatHex(aesEcb(Cipher.ENCRYPT_MODE, plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("NetEase eapi encryption failed", e);
        }
    }

    /** Decrypts an eapi-encrypted response body (hex). */
    public static String eapiDecrypt(String hex) {
        try {
            return new String(aesEcb(Cipher.DECRYPT_MODE, HexFormat.of().parseHex(hex.trim())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("NetEase eapi decryption failed", e);
        }
    }

    /** Recovers the plaintext of an eapi request {@code params} (the inverse of {@link #eapi}, for tests). */
    static String eapiRequestPlaintext(String paramsHex) {
        return eapiDecrypt(paramsHex);
    }

    private static byte[] aesEcb(int mode, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        return cipher.doFinal(data);
    }
}

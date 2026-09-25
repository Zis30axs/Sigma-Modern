package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.mentalfrostbyte.jello.music.netease.NeteaseAccount;
import org.junit.jupiter.api.Test;

class ModernMusicAccountTest {
    /** The login code, drawn the way the page draws it (4-module quiet zone, whole pixels per module), reads back. */
    @Test
    void theLoginCodeScansBackToTheLoginUrl() throws Exception {
        String url = NeteaseAccount.QR_PREFIX + "0f8c2c3a-6e1b-4a7e-9d0b-6c1f2a3b4c5d";
        BitMatrix code = ModernMusicAccount.encode(url);
        assertNotNull(code);
        int module = 3, quiet = 4, size = (code.getWidth() + quiet * 2) * module;
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < code.getHeight(); y++) {
            for (int x = 0; x < code.getWidth(); x++) {
                if (!code.get(x, y)) continue;
                for (int dy = 0; dy < module; dy++) {
                    for (int dx = 0; dx < module; dx++) pixels[((quiet + y) * module + dy) * size + (quiet + x) * module + dx] = 0xFF0A1420;
                }
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, pixels)));
        assertEquals(url, new QRCodeReader().decode(bitmap).getText());
    }
}

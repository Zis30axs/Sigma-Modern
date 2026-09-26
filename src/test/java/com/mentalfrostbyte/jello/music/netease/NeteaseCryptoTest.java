package com.mentalfrostbyte.jello.music.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The request encryption that the live endpoints accepted when this port was probed. */
class NeteaseCryptoTest {
    @Test
    void eapiRoundTripsAndCarriesTheDigest() {
        String json = "{\"ids\":\"[1330348068]\",\"level\":\"standard\"}";
        String params = NeteaseCrypto.eapi("/api/song/enhance/player/url/v1", json);
        assertTrue(params.matches("[0-9A-F]+"), "upper-case hex");
        String plain = NeteaseCrypto.eapiRequestPlaintext(params);
        String[] parts = plain.split(NeteaseCrypto.EAPI_SEPARATOR);
        assertEquals("/api/song/enhance/player/url/v1", parts[0]);
        assertEquals(json, parts[1]);
        assertEquals(32, parts[2].length(), "md5 hex digest");
    }

    @Test
    void weapiProducesParamsAndAFullLengthKey() {
        String[] enc = NeteaseCrypto.weapi("{\"s\":\"test\"}");
        assertTrue(enc[0].length() > 20);
        assertEquals(256, enc[1].length(), "RSA output is padded to 256 hex digits");
    }
}

package com.example.aaphone.protocol.handshake;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS server identity for the phone side of the AA handshake.
 *
 * IMPORTANT: per docs/design/0003-aasdk-protocol-notes.md, aasdk (acting as
 * the head unit) sets itself up as the TLS <b>client</b>
 * ({@code SSL_set_connect_state}), embedding a hardcoded certificate as ITS
 * OWN identity. That means the real head unit is the TLS client and we (the
 * phone) must be the TLS <b>server</b> — and we need our own identity, not a
 * copy of the head unit's.
 *
 * The certificate/key below were generated fresh, specifically for this
 * project ({@code openssl req -x509 -newkey rsa:2048 -nodes -keyout
 * aaphone_key.pem -out aaphone_cert.pem -days 3650 -subj "/CN=aa-phone-app"})
 * — they are NOT copied from aasdk. Since AA does no CA chain validation
 * (see docs/design/0001-phone-side-architecture.md), any self-signed
 * identity should be acceptable; this one is simply ours.
 *
 * TODO: not yet validated against a real head unit — whether it inspects
 * certificate contents beyond using it to establish the session is
 * unconfirmed.
 */
public final class AaServerIdentity {

    private static final String CERTIFICATE_PEM =
        "-----BEGIN CERTIFICATE-----\n"
            + "MIICqjCCAZICCQCBhs/t0PleejANBgkqhkiG9w0BAQsFADAXMRUwEwYDVQQDDAxh\n"
            + "YS1waG9uZS1hcHAwHhcNMjYwODA2MjIxOTQ3WhcNMzYwODAzMjIxOTQ3WjAXMRUw\n"
            + "EwYDVQQDDAxhYS1waG9uZS1hcHAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK\n"
            + "AoIBAQCpvTinI7URkGrLJDFPWieWO80BDAhUl1CQQYfEoh8BOSCfgST4Zj9SoVBI\n"
            + "YWiaUTHUYmAga9OXsBG4YkoSXk0enRy5w1Ld0gZh2sE2L5P8beyVCAb1c1Wj3t1Z\n"
            + "wU1cXVFEz15sdOYqEuQRQ46guoU+PxH5qNo6A0Wc/km2JYS14YAT+gfViU0e3sqB\n"
            + "CJ/aAbpQ1spa+1Fo0DT1YPDC1WyGv3L/BTZSutU0GMlafdwv+qjZaioisfUphIKS\n"
            + "vUqpoZiDZdpE1y4ZhqpoxqvJSjiIqkEZN+f+Tmvmh1Jw4KztmZmvGg59S4uohb+n\n"
            + "HErxbVQpWD/EWWQNdvYggd7jk3jdAgMBAAEwDQYJKoZIhvcNAQELBQADggEBADbs\n"
            + "rZgtT52MKybeLDTrOIB2SGMnZxob8iIKC3FzvmuXnRi39iNcCajKDvmDhl0frLVG\n"
            + "F+xbun7DdornlNIML4uci4kOoEgbDf1vpWBJR2QCTxjIPpbjZ40TgTL3S2pWrXf5\n"
            + "IYBPl3OheBUUiCZWo41g2e2F9sUavHT4ALptLVmH7/a/1Kgq/gtD4S0en4p2WVTU\n"
            + "qynjzQVCvTrGug9ac8giF6nErLfegVMHsDhwyZ6DmTrPoc/jmsj+TZBtGHuj8BKV\n"
            + "Ru6W44fsBcqTM4QtIZINi+YX54sYSep4k0Qd8s2l2YQuMQPyVJ6fdCAwynKSaHSv\n"
            + "DZ2LHynlEbtNW2wJu3o=\n"
            + "-----END CERTIFICATE-----\n";

    private static final String PRIVATE_KEY_PEM =
        "-----BEGIN PRIVATE KEY-----\n"
            + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCpvTinI7URkGrL\n"
            + "JDFPWieWO80BDAhUl1CQQYfEoh8BOSCfgST4Zj9SoVBIYWiaUTHUYmAga9OXsBG4\n"
            + "YkoSXk0enRy5w1Ld0gZh2sE2L5P8beyVCAb1c1Wj3t1ZwU1cXVFEz15sdOYqEuQR\n"
            + "Q46guoU+PxH5qNo6A0Wc/km2JYS14YAT+gfViU0e3sqBCJ/aAbpQ1spa+1Fo0DT1\n"
            + "YPDC1WyGv3L/BTZSutU0GMlafdwv+qjZaioisfUphIKSvUqpoZiDZdpE1y4Zhqpo\n"
            + "xqvJSjiIqkEZN+f+Tmvmh1Jw4KztmZmvGg59S4uohb+nHErxbVQpWD/EWWQNdvYg\n"
            + "gd7jk3jdAgMBAAECggEAeaAMqDb918gTvdwBOUrwcsYG72kfzv1dzQAnuM4yST4W\n"
            + "EjUHuLYLSbotPLAPtAPaIMUc/B5HT7np+KT1TpQjznvlyXYcOrXzvMpzB9CvyYE6\n"
            + "tM87rUUnaHSZR2crd7OmOBgfILfi9OL0aSpvWNxN/XxT9QD43fjaONQ9HTdlEK2J\n"
            + "vW0MLJSg2P/2a4vaHs8CaHebsO2xSV8OKyPKC/GVLubQTSrr20XCbx90rzQuleoL\n"
            + "jK5WfmJIQze6RtmkUnlk6KhcTsLyVv0nd7RQ/I0DP651HIk+2tCesozDp+QRAsuj\n"
            + "vzuin4Rg07T16UXqcEhNbDa5xRE88hxSMYeN5sEuAQKBgQDUk1Zat3GrtuURBbJH\n"
            + "D2dsaJteeHoy/ldSjVjCpk+bhR9U7BD3ud7mneeuCn3icS9DSVu8Oz5R9qUB5ZFg\n"
            + "PIDXjWrQxYsDxe0Andsjj8Jus5YfRXhup0W+LxQluhQP9MkknywH0ANhDW8hRgeJ\n"
            + "8n48sKeNhQafH4Xmuwkv/cdX6QKBgQDMab/4sAMlpj+X7OO0Z6RUDyqQjyez9xL3\n"
            + "dXQiVnZ1gbDeYSHQI4j+yJi1AcyzTRh+eM0U9FAZ93lgwrG+DQU8+yREPzFLas3r\n"
            + "GKir+0OUu0BUAfw02P/RMvuO0JNCT0VM969jxTroGQHOn448S6zm3Q+8plxwp1Pc\n"
            + "S5moGnw01QKBgQCyltHexu1NuQs7QNDlGFDoZ/3X4Vmwi7OrHCrs5TJOUwnem7Ep\n"
            + "nlNg5lplAlV+L17opbHXMuKJk7BPJqH6+vm3ngyWNtAyrE8PzI71kmpj/KZrwT6L\n"
            + "oCZcwEqp42nFef6esMcaDS05lUK+7omY4EwkCrnJkG/esWoaTRpL4mZeOQKBgH4K\n"
            + "br6O+UHLwsaQ4M6qYV8bgulj+90x6dX/7D4IBj7qWv6j8c9/OcewNMjXdTrRvNqu\n"
            + "7fWPt5xrRcJuCl7fdG1nhbM3K9QO5S2jJM32vnMFCuNB3htP+l0qDIbBASwP6PFO\n"
            + "gC0KvgnC8aRQKcnv94raoAt4oOMtqb3aN8K5ogCJAoGBAM73oJVDSYPYaWkyyrBr\n"
            + "xTTHSrpB9QcqpBZcho6bPjOR2NQyYaUMZcTVWX8Vjqqb+bBQf6Er/uX892a29qsH\n"
            + "aoRdkI5ewnzyYXwbiXpMDYJ/Ee3sV7HVsokBM6j7ZzrS9rrdL5x8EkgxcXLNKNwP\n"
            + "MA63A1dKAvT2KgxewMUKi8yO\n"
            + "-----END PRIVATE KEY-----\n";

    private AaServerIdentity() {
    }

    public static SSLContext buildServerContext() {
        try {
            Certificate certificate = parseCertificate(CERTIFICATE_PEM);
            PrivateKey privateKey = parsePrivateKey(PRIVATE_KEY_PEM);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("aa-phone", privateKey, new char[0], new Certificate[]{certificate});

            KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, new char[0]);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                keyManagerFactory.getKeyManagers(),
                new TrustManager[]{permissiveTrustManager()},
                null
            );
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the phone's TLS server identity", e);
        }
    }

    // AA does no CA chain validation (see docs/design/0001) -- the head unit's
    // certificate is accepted regardless of chain of trust.
    private static X509TrustManager permissiveTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static Certificate parseCertificate(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(stripPemHeaders(pem, "CERTIFICATE"));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return factory.generateCertificate(new ByteArrayInputStream(der));
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(stripPemHeaders(pem, "PRIVATE KEY"));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String stripPemHeaders(String pem, String label) {
        return pem
            .replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replaceAll("\\s", "");
    }
}

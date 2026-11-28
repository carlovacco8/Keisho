package io.github.carlovacco8.keisho;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * The phone light gun's certificates, made on this Shield.
 *
 * A home certificate authority is created once and kept in the app's private
 * files: its key never leaves the Shield. The phone installs and trusts its
 * certificate once (served at http://address:18080/m2nexus.cer); after that
 * every server certificate below verifies and Safari shows no warning.
 *
 * The authority is limited by name constraints to the home network's
 * addresses (192.168/16, 10/8, 172.16/12) and m2nexus.local, so trusting it
 * cannot vouch for any site on the internet.
 *
 * For each address the phone connects to, a server certificate is issued for
 * that address (800 days, serverAuth: what iOS asks of a server).
 */
final class GunCerts {
    private static final char[] PASS = "m2nexus".toCharArray();

    private final File        dir;
    private PrivateKey        caKey;
    private X509Certificate   caCert;
    private final Map<String, SSLSocketFactory> perAddress = new HashMap<>();

    GunCerts(File dir) {
        this.dir = dir;
    }

    /** The authority's certificate (DER), creating the authority if needed. */
    synchronized byte[] authority() throws Exception {
        ensureAuthority();
        return caCert.getEncoded();
    }

    /** A TLS server for connections made to {@code local}. */
    synchronized SSLSocketFactory serverFor(InetAddress local) throws Exception {
        String ip = local.getHostAddress();
        SSLSocketFactory f = perAddress.get(ip);
        if (f != null) return f;
        ensureAuthority();
        KeyPair         kp   = rsa();
        X509Certificate leaf = issueLeaf(kp.getPublic(), local.getAddress());
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("gun", kp.getPrivate(), PASS, new Certificate[] {leaf, caCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        f = ctx.getSocketFactory();
        perAddress.put(ip, f);
        return f;
    }

    // ------------------------------------------------------------------

    private void ensureAuthority() throws Exception {
        if (caCert != null) return;
        File keyFile  = new File(dir, "gun-ca.key");
        File certFile = new File(dir, "gun-ca.der");
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                caKey  = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                caCert = parse(Files.readAllBytes(certFile.toPath()));
                if (caCert.getNotAfter().after(new Date(System.currentTimeMillis() + 30L * 86400000L))) return;
            } catch (Exception ignored) {
                // Unreadable: make a new one.
            }
        }
        KeyPair kp = rsa();
        caKey  = kp.getPrivate();
        caCert = issueAuthority(kp);
        write(keyFile, caKey.getEncoded());
        write(certFile, caCert.getEncoded());
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        return g.generateKeyPair();
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static X509Certificate parse(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static final long DAY = 86400000L;

    private X509Certificate issueAuthority(KeyPair kp) throws Exception {
        byte[] name = name("M2 NEXUS home certificate", "M2 NEXUS");
        byte[] ski  = keyId(kp.getPublic());
        long   now  = System.currentTimeMillis();
        byte[] ext  = seq(
                ext("2.5.29.19", true, seq(bool(true), integer(BigInteger.ZERO))),   // CA, path length 0
                ext("2.5.29.15", true, bits(new byte[] {0x06}, 1)),                   // keyCertSign, cRLSign
                ext("2.5.29.14", false, octet(ski)),
                ext("2.5.29.30", true, seq(tagged(0xA0,                              // permitted subtrees
                        concat(subtree(0x87, ipMask(192, 168, 0, 0, 255, 255, 0, 0)),
                               subtree(0x87, ipMask(10, 0, 0, 0, 255, 0, 0, 0)),
                               subtree(0x87, ipMask(172, 16, 0, 0, 255, 240, 0, 0)),
                               subtree(0x82, "m2nexus.local".getBytes(StandardCharsets.US_ASCII)))))));
        return sign(name, name, now - DAY, now + 3650 * DAY, kp.getPublic(), ext, kp.getPrivate());
    }

    private X509Certificate issueLeaf(PublicKey pub, byte[] ip) throws Exception {
        byte[] issuer  = caCert.getSubjectX500Principal().getEncoded();
        byte[] subject = name("m2nexus.local", "M2 NEXUS");
        long   now     = System.currentTimeMillis();
        byte[] ext = seq(
                ext("2.5.29.19", true, seq()),                                        // not a CA
                ext("2.5.29.15", true, bits(new byte[] {(byte) 0xA0}, 5)),            // digitalSignature, keyEncipherment
                ext("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))),               // serverAuth
                ext("2.5.29.17", false, seq(tlv(0x87, ip),
                                            tlv(0x82, "m2nexus.local".getBytes(StandardCharsets.US_ASCII)))),
                ext("2.5.29.14", false, octet(keyId(pub))),
                ext("2.5.29.35", false, seq(tlv(0x80, keyId(caCert.getPublicKey())))));
        return sign(issuer, subject, now - DAY, now + 800 * DAY, pub, ext, caKey);
    }

    private static X509Certificate sign(byte[] issuer, byte[] subject, long from, long to, PublicKey pub,
                                        byte[] extensions, PrivateKey by) throws Exception {
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        byte[] alg = seq(oid("1.2.840.113549.1.1.11"), new byte[] {0x05, 0x00});     // sha256WithRSA
        byte[] tbs = seq(
                tagged(0xA0, integer(BigInteger.valueOf(2))),                         // v3
                integer(new BigInteger(1, serial)),
                alg,
                issuer,
                seq(time(from), time(to)),
                subject,
                pub.getEncoded(),
                tagged(0xA3, extensions));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(by);
        s.update(tbs);
        return parse(seq(tbs, alg, bits(s.sign(), 0)));
    }

    // -- DER --------------------------------------------------------------

    private static byte[] keyId(PublicKey pub) throws Exception {
        // SHA-1 of the subjectPublicKey bits (RFC 5280 method 1).
        byte[] spki = pub.getEncoded();
        byte[] bitString = lastElement(spki);
        return MessageDigest.getInstance("SHA-1").digest(java.util.Arrays.copyOfRange(bitString, 1, bitString.length));
    }

    /** The content of the last element inside a DER SEQUENCE. */
    private static byte[] lastElement(byte[] seq) {
        int[] hdr = header(seq, 0);
        int   at  = hdr[0];
        int   end = hdr[0] + hdr[1];
        byte[] last = null;
        while (at < end) {
            int[] h = header(seq, at);
            last = java.util.Arrays.copyOfRange(seq, h[0], h[0] + h[1]);
            at = h[0] + h[1];
        }
        return last;
    }

    /** {content start, content length} of the element at {@code at}. */
    private static int[] header(byte[] d, int at) {
        int len = d[at + 1] & 0xff;
        int p   = at + 2;
        if ((len & 0x80) != 0) {
            int n = len & 0x7f;
            len = 0;
            for (int i = 0; i < n; i++) len = (len << 8) | (d[p++] & 0xff);
        }
        return new int[] {p, len};
    }

    private static byte[] subtree(int tag, byte[] value) {
        return seq(tlv(tag, value));
    }

    private static byte[] ipMask(int a, int b, int c, int d, int ma, int mb, int mc, int md) {
        return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d, (byte) ma, (byte) mb, (byte) mc, (byte) md};
    }

    private static byte[] name(String cn, String org) {
        return seq(set(seq(oid("2.5.4.3"), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))),
                   set(seq(oid("2.5.4.10"), tlv(0x0C, org.getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] ext(String id, boolean critical, byte[] value) {
        return critical ? seq(oid(id), bool(true), octet(value)) : seq(oid(id), octet(value));
    }

    private static byte[] time(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(ms)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }
    private static byte[] bool(boolean v)       { return tlv(0x01, new byte[] {(byte) (v ? 0xff : 0)}); }
    private static byte[] octet(byte[] v)       { return tlv(0x04, v); }
    private static byte[] seq(byte[]... parts)  { return tlv(0x30, concat(parts)); }
    private static byte[] set(byte[]... parts)  { return tlv(0x31, concat(parts)); }
    private static byte[] tagged(int tag, byte[] content) { return tlv(tag, content); }

    private static byte[] bits(byte[] v, int unused) {
        byte[] b = new byte[v.length + 1];
        b[0] = (byte) unused;
        System.arraycopy(v, 0, b, 1, v.length);
        return tlv(0x03, b);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = v.length;
        if (n < 128) {
            out.write(n);
        } else if (n < 256) {
            out.write(0x81);
            out.write(n);
        } else if (n < 65536) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write(n >> 8);
            out.write(n);
        }
        out.write(v, 0, n);
        return out.toByteArray();
    }
}

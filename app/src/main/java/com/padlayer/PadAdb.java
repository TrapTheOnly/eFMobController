package com.padlayer;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * The phone's own wireless-debugging identity. The key stays on the phone,
 * so pairing survives a reboot.
 */
final class PadAdb extends AbsAdbConnectionManager {
    private static PadAdb instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized PadAdb get(Context context) throws Exception {
        if (instance == null) {
            instance = new PadAdb(context.getApplicationContext());
        }
        return instance;
    }

    private PadAdb(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");
        setTimeout(15, java.util.concurrent.TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
        File keyFile = new File(context.getFilesDir(), "adb-private.key");
        File certFile = new File(context.getFilesDir(), "adb-cert.der");
        if (keyFile.exists() && certFile.exists()) {
            privateKey = readKey(keyFile);
            certificate = readCert(certFile);
        } else {
            java.security.KeyPair pair = generate();
            privateKey = pair.getPrivate();
            certificate = sign(pair);
            write(keyFile, privateKey.getEncoded());
            write(certFile, certificate.getEncoded());
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "Padlayer";
    }

    private static java.security.KeyPair generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        return generator.generateKeyPair();
    }

    private static Certificate sign(java.security.KeyPair pair) throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        X500Name name = new X500Name("CN=Padlayer");
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000);
        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.currentTimeMillis()),
                notBefore,
                notAfter,
                name,
                pair.getPublic())
                .build(signer(pair.getPrivate()));
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static ContentSigner signer(PrivateKey key) throws Exception {
        return new JcaContentSignerBuilder("SHA256withRSA").build(key);
    }

    private static PrivateKey readKey(File file) throws Exception {
        byte[] encoded = read(file);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static Certificate readCert(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int n = in.read(buffer, offset, buffer.length - offset);
                if (n < 0) {
                    break;
                }
                offset += n;
            }
            return buffer;
        }
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}

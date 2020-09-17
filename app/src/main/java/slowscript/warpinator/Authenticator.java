package slowscript.warpinator;

import android.util.Base64;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.openjax.security.nacl.TweetNaclFast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;

public class Authenticator {
    private static String TAG = "AUTH";
    public static String DEFAULT_GROUP_CODE = "Warpinator";

    static long day = 1000L * 60L * 60L * 24;

    public static long expireTime = 30L * day;
    public static String groupCode = DEFAULT_GROUP_CODE;

    static String cert_begin = "-----BEGIN CERTIFICATE-----\n";
    static String cert_end = "-----END CERTIFICATE-----";

    public static byte[] getBoxedCertificate() {
        byte[] bytes = new byte[0];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            final byte[] key = md.digest(groupCode.getBytes(StandardCharsets.UTF_8));
            TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
            byte[] nonce = TweetNaclFast.makeSecretBoxNonce();
            byte[] res = box.box(getServerCertificate(), nonce);
            /*
            final byte[] key = md.digest("hello".getBytes("UTF-8"));
            TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
            byte[] nonce = "123456789012345678901234".getBytes();
            byte[] res = box.box("Hello".getBytes(), nonce);
            */
            bytes = new byte[24 + res.length];
            System.arraycopy(nonce, 0, bytes, 0, 24);
            System.arraycopy(res, 0, bytes, 24, res.length);
        } catch (Exception e) {
            Log.wtf(TAG, "WADUHEK", e);
        } //This shouldn't fail
        return bytes;
    }

    public static byte[] getServerCertificate() {
        //Try loading it first
        try {
            Log.d(TAG, "Loading server certificate...");
            return loadCertificate(".self");
        } catch (Exception ignored) {}

        //Create new one if doesn't exist yet
        byte[] cert = createCertificate(Utils.getDeviceName());
        saveCertOrKey(".self.pem", cert, false);
        return cert;
    }

    public static File getCertificateFile(String hostname) {
        File certsDir = Utils.getCertsDir();
        return new File(certsDir, hostname + ".pem");
    }

    static byte[] createCertificate(String hostname) {
        try {
            Log.d(TAG, "Creating new server certificate...");

            String ip = Utils.getIPAddress();
            Security.addProvider(new BouncyCastleProvider());
            //Create KeyPair
            KeyPair kp = createKeyPair("RSA", 2048);

            long now = System.currentTimeMillis();

            //Build certificate
            X500Name name = new X500Name("CN="+hostname);
            BigInteger serial = new BigInteger(Long.toString(now)); //Use current time as serial num
            Date notBefore = new Date(now - day);
            Date notAfter = new Date(now + expireTime);

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, serial, notBefore, notAfter, name, kp.getPublic());
            builder.addExtension(X509Extensions.SubjectAlternativeName, true, new GeneralNames(new GeneralName(GeneralName.iPAddress, ip)));

            //Sign certificate
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
            X509CertificateHolder cert = builder.build(signer);

            //Save private key
            byte[] privKeyBytes = kp.getPrivate().getEncoded();
            saveCertOrKey(".self.key-pem", privKeyBytes, true);

            return cert.getEncoded();
        }
        catch(Exception e) {
            Log.e(TAG, "Failed to create certificate");
            e.printStackTrace();
            return null;
        }
    }

    public static void saveBoxedCert(byte[] bytes, String remoteUuid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            final byte[] key = md.digest(groupCode.getBytes("UTF-8"));
            TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
            byte[] nonce = new byte[24];
            byte[] ciph = new byte[bytes.length - 24];
            System.arraycopy(bytes, 0, nonce, 0, 24);
            System.arraycopy(bytes, 24, ciph, 0, bytes.length - 24);
            byte[] cert = box.open(ciph, nonce);
            if (cert == null) {
                Log.w(TAG, "Failed to unbox cert. Wrong group code?");
                return;
            }

            saveCertOrKey(remoteUuid + ".pem", cert, false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbox and save certificate", e);
        }
    }

    private static void saveCertOrKey(String filename, byte[] bytes, boolean isPrivateKey) {
        File certsDir = Utils.getCertsDir();
        if (!certsDir.exists())
            certsDir.mkdir();
        File cert = new File(certsDir, filename);

        String begin = cert_begin;
        String end = cert_end;
        if (isPrivateKey) {
            begin = "-----BEGIN PRIVATE KEY-----\n";
            end = "-----END PRIVATE KEY-----";
        }
        String cert64 = Base64.encodeToString(bytes, Base64.DEFAULT);
        String certString = begin + cert64 + end;
        try (FileOutputStream stream = new FileOutputStream(cert, false)) {
            stream.write(certString.getBytes());
        } catch (Exception e) {
            Log.w(TAG, "Failed to save certificate or private key: " + filename);
            e.printStackTrace();
        }
    }

    private static byte[] loadCertificate(String hostname) throws IOException {
        File cert = getCertificateFile(hostname);
        return Utils.readAllBytes(cert);
    }

    private static KeyPair createKeyPair(String algorithm, int bitCount) throws NoSuchAlgorithmException
    {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(algorithm);
        keyPairGenerator.initialize(bitCount, new SecureRandom());

        return keyPairGenerator.genKeyPair();
    }
}

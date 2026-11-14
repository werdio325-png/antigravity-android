package dev.continuum.antigravity.core;

import android.content.Context;
import android.util.Log;

import dev.continuum.antigravity.util.CoreUrl;
import dev.continuum.antigravity.util.ProcessUtils;

import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;

/** One-shot HTTPS and certificate-pin probe of the embedded core origin. */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";
    public static final long READY_TIMEOUT_MS = 60000;
    /** Re-probe gap while the core boots. */
    private static final long RETRY_SLEEP_MS = 60;
    private static volatile byte[] expectedCertDigest;

    private CoreReadyProbe() {
    }

    public static boolean probeOnce(Context context, int port) {
        HttpsURLConnection connection = null;
        try {
            if (context == null) {
                return false;
            }
            URL url = new URL(CoreUrl.startUrl(port));
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < 100 || responseCode > 599) {
                return false;
            }
            Certificate[] chain = connection.getServerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                return false;
            }
            byte[] expected = expectedCertDigest(context);
            byte[] actual = sha256(chain[0].getEncoded());
            return expected != null && MessageDigest.isEqual(expected, actual);
        } catch (Exception notReadyOrUntrusted) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] expectedCertDigest(Context context) throws Exception {
        byte[] cached = expectedCertDigest;
        if (cached != null) {
            return cached;
        }
        synchronized (CoreReadyProbe.class) {
            cached = expectedCertDigest;
            if (cached != null) {
                return cached;
            }
            int resourceId = context.getResources().getIdentifier(
                    "agy_core_cert", "raw", context.getPackageName());
            if (resourceId == 0) {
                Log.e(TAG, "bundled core certificate resource is missing");
                return null;
            }
            try (InputStream input = context.getResources().openRawResource(resourceId)) {
                Certificate certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input);
                if (!(certificate instanceof X509Certificate)) {
                    return null;
                }
                cached = sha256(certificate.getEncoded());
                expectedCertDigest = cached;
                return cached;
            }
        }
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}

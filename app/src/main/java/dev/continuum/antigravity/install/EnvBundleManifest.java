package dev.continuum.antigravity.install;

import android.content.res.AssetManager;

import dev.continuum.antigravity.util.IoUtil;
import dev.continuum.antigravity.util.Sha256;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Parsed, deterministic metadata for the APK-bundled Bionic environment. */
public final class EnvBundleManifest {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9+._-]*$");
    private static final int SUPPORTED_SCHEMA = 1;

    public final String bundleId;
    public final String bootstrapSha256;
    public final String overlaySha256;
    public final String pkgSha256;
    public final int bootstrapSymlinkCount;
    public final List<String> seedPackages;

    private EnvBundleManifest(String bundleId, String bootstrapSha256,
                              String overlaySha256, String pkgSha256,
                              int bootstrapSymlinkCount, List<String> seedPackages) {
        this.bundleId = bundleId;
        this.bootstrapSha256 = bootstrapSha256;
        this.overlaySha256 = overlaySha256;
        this.pkgSha256 = pkgSha256;
        this.bootstrapSymlinkCount = bootstrapSymlinkCount;
        this.seedPackages = Collections.unmodifiableList(seedPackages);
    }

    public static EnvBundleManifest load(AssetManager assets) {
        try (InputStream in = assets.open(EnvPaths.ASSET_MANIFEST)) {
            JSONObject json = new JSONObject(IoUtil.readAll(in, "UTF-8"));
            int schema = json.getInt("schema");
            if (schema != SUPPORTED_SCHEMA) {
                throw new IllegalStateException("unsupported env manifest schema: " + schema);
            }
            String bundleId = requireSha256(json.getString("bundleId"), "bundleId");
            String bootstrapSha = requireSha256(json.getString("bootstrapSha256"), "bootstrapSha256");
            String overlaySha = requireSha256(json.getString("overlaySha256"), "overlaySha256");
            String pkgSha = requireSha256(json.getString("pkgSha256"), "pkgSha256");
            int symlinks = json.getInt("bootstrapSymlinkCount");
            if (symlinks < 1) {
                throw new IllegalStateException("env manifest contains no bootstrap symlinks");
            }
            JSONArray packagesJson = json.getJSONArray("seedPackages");
            List<String> packages = new ArrayList<>();
            for (int i = 0; i < packagesJson.length(); i++) {
                String name = packagesJson.getString(i);
                if (!PACKAGE.matcher(name).matches()) {
                    throw new IllegalStateException("invalid seed package name: " + name);
                }
                if (!packages.contains(name)) {
                    packages.add(name);
                }
            }
            return new EnvBundleManifest(bundleId, bootstrapSha, overlaySha,
                    pkgSha, symlinks, packages);
        } catch (IOException e) {
            throw new IllegalStateException("required asset missing: " + EnvPaths.ASSET_MANIFEST, e);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("invalid env manifest: " + e.getMessage(), e);
        }
    }

    public void verifyBootstrap(File archive) throws IOException {
        try {
            String actual = Sha256.of(archive);
            if (!bootstrapSha256.equals(actual)) {
                throw new IOException("bootstrap sha256 mismatch: got " + actual
                        + " want " + bootstrapSha256);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot hash bootstrap archive", e);
        }
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalStateException("invalid env manifest field: " + name);
        }
        return value;
    }
}

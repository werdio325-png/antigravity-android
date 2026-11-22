package com.agy.runtime;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.agy.install.EnvPaths;
import com.agy.util.AtomicFileWriter;
import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Materializes a fully-onboarded state before the core starts, so the Welcome
 * wizard is skipped, and non-destructively seeds default Android MCP servers
 * ({@code android-device} and {@code chrome_devtools}) and bundled agent skills.
 * Idempotent and non-destructive: never overwrites user-defined MCP servers
 * or existing customized files.
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";
    private static final String ANDROID_MCP_SERVER_KEY = "android-device";
    private static final String CHROME_DEVTOOLS_MCP_SERVER_KEY = "chrome_devtools";

    private OnboardingSeeder() {
    }

    public static void seed(Context appContext, File appData, File projects) {
        try {
            if (!appData.exists() && !appData.mkdirs()) {
                Log.w(TAG, "cannot create " + appData);
                return;
            }
            seedStateFile(appContext, "jetski_state.pbtxt", new File(appData, "jetski_state.pbtxt"));
            seedStateFile(appContext, "antigravity_state.pbtxt",
                    new File(appData, "antigravity_state.pbtxt"));

            // Seed default agent rules (AGENTS.md) into ~/.gemini/config/AGENTS.md
            File configDir = projects != null ? projects.getParentFile() : null;
            if (configDir != null && (configDir.isDirectory() || configDir.mkdirs())) {
                seedStateFile(appContext, "AGENTS.md", new File(configDir, "AGENTS.md"));
            }

            // Non-destructively register the bundled android-device and chrome_devtools stdio MCP servers
            seedMcpConfigs(appContext, appData, configDir);

            // Non-destructively copy bundled Antigravity skills
            seedSkills(appContext, appData, configDir);

            // The project store resolves its projects here; without the
            // pseudo-project file it logs project_store_get_file_missing.
            if (projects != null) {
                if (!projects.exists() && !projects.mkdirs()) {
                    Log.w(TAG, "cannot create " + projects);
                    return;
                }
                File outside = new File(projects, "outside-of-project.json");
                if (!outside.isFile()) {
                    AtomicFileWriter.writeText(outside, "{}\n");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onboarding seed failed (continuing): " + e.getMessage());
        }
    }

    private static void seedMcpConfigs(Context appContext, File appData, File configDir) {
        File filesDir = appContext.getFilesDir();
        File prefix = EnvPaths.prefix(appContext);
        File runtimeBin = new File(filesDir, RuntimePaths.BIN_DIR);
        File androidMcpBridge = new File(runtimeBin, "agy-mcp-android");
        File browserMcpBridge = new File(runtimeBin, "agy-mcp-browser");

        List<File> targets = new ArrayList<>();
        // v2.34.0 global MCP config path: ~/.gemini/config/mcp_config.json
        if (configDir != null) {
            targets.add(new File(configDir, "mcp_config.json"));
        }
        // Keep appData and legacy antigravity paths in sync as well
        addUniqueFile(targets, new File(appData, "mcp_config.json"));
        File geminiDir = appData.getParentFile();
        if (geminiDir != null) {
            addUniqueFile(targets, new File(new File(geminiDir, "antigravity"), "mcp_config.json"));
        }

        String homePath = filesDir.getAbsolutePath();
        String prefixPath = prefix.getAbsolutePath();
        String runtimeBinPath = runtimeBin.getAbsolutePath();

        for (File target : targets) {
            try {
                mergeBundledMcpServers(
                        target,
                        androidMcpBridge.getAbsolutePath(),
                        browserMcpBridge.getAbsolutePath(),
                        homePath,
                        prefixPath,
                        runtimeBinPath);
            } catch (Exception e) {
                Log.w(TAG, "failed seeding mcp_config at " + target + ": " + e.getMessage());
            }
        }
    }

    private static void mergeBundledMcpServers(File target,
                                               String androidMcpCmd,
                                               String browserMcpCmd,
                                               String homePath,
                                               String prefixPath,
                                               String runtimeBinPath) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "cannot create mcp_config parent dir: " + parent);
            return;
        }

        JSONObject root;
        if (target.isFile() && target.length() > 0L) {
            String existingText;
            try (FileInputStream in = new FileInputStream(target)) {
                existingText = IoUtil.readAll(in, "UTF-8").trim();
            }
            if (existingText.isEmpty()) {
                root = new JSONObject();
            } else {
                try {
                    root = new JSONObject(existingText);
                } catch (Exception parseErr) {
                    Log.w(TAG, "leaving invalid user mcp_config.json untouched: " + target);
                    return;
                }
            }
        } else {
            root = new JSONObject();
        }

        JSONObject mcpServers = root.optJSONObject("mcpServers");
        if (mcpServers == null) {
            mcpServers = new JSONObject();
            root.put("mcpServers", mcpServers);
        }

        boolean changed = false;
        changed |= ensureMcpServerEntry(
                mcpServers,
                ANDROID_MCP_SERVER_KEY,
                androidMcpCmd,
                "/usr/bin/agy-mcp-android",
                homePath,
                prefixPath,
                runtimeBinPath);
        changed |= ensureMcpServerEntry(
                mcpServers,
                CHROME_DEVTOOLS_MCP_SERVER_KEY,
                browserMcpCmd,
                "/usr/bin/agy-mcp-browser",
                homePath,
                prefixPath,
                runtimeBinPath);

        if (!changed) {
            Log.d(TAG, "bundled MCP servers already current in " + target);
            return;
        }

        AtomicFileWriter.writeText(target, root.toString(2) + "\n");
        Log.i(TAG, "seeded/updated bundled MCP servers in " + target);
    }

    private static boolean ensureMcpServerEntry(JSONObject mcpServers,
                                                String serverKey,
                                                String bridgeCommand,
                                                String legacyPrefixSuffix,
                                                String homePath,
                                                String prefixPath,
                                                String runtimeBinPath) throws Exception {
        String ldLibraryPath = prefixPath + "/lib";
        String pathEnv = runtimeBinPath + ":" + prefixPath + "/bin:/system/bin";

        if (mcpServers.has(serverKey)) {
            JSONObject existing = mcpServers.optJSONObject(serverKey);
            if (existing == null) {
                return false;
            }
            String existingCmd = existing.optString("command", "").trim();
            boolean isStaleLegacy = existingCmd.endsWith(legacyPrefixSuffix);
            boolean isOurBridge = bridgeCommand.equals(existingCmd);
            if (!isStaleLegacy && !isOurBridge) {
                // User customized this server entry; leave untouched.
                return false;
            }
            boolean updated = false;
            if (isStaleLegacy) {
                existing.put("command", bridgeCommand);
                updated = true;
            }
            JSONObject envObj = existing.optJSONObject("env");
            if (envObj == null) {
                envObj = new JSONObject();
                existing.put("env", envObj);
                updated = true;
            }
            if (!homePath.equals(envObj.optString("HOME", ""))) {
                envObj.put("HOME", homePath);
                updated = true;
            }
            if (!prefixPath.equals(envObj.optString("PREFIX", ""))) {
                envObj.put("PREFIX", prefixPath);
                updated = true;
            }
            if (!ldLibraryPath.equals(envObj.optString("LD_LIBRARY_PATH", ""))) {
                envObj.put("LD_LIBRARY_PATH", ldLibraryPath);
                updated = true;
            }
            if (!pathEnv.equals(envObj.optString("PATH", ""))) {
                envObj.put("PATH", pathEnv);
                updated = true;
            }
            return updated;
        }

        JSONObject serverSpec = new JSONObject();
        serverSpec.put("command", bridgeCommand);
        serverSpec.put("args", new JSONArray());
        JSONObject envObj = new JSONObject();
        envObj.put("HOME", homePath);
        envObj.put("PREFIX", prefixPath);
        envObj.put("LD_LIBRARY_PATH", ldLibraryPath);
        envObj.put("PATH", pathEnv);
        serverSpec.put("env", envObj);

        mcpServers.put(serverKey, serverSpec);
        return true;
    }

    private static void seedSkills(Context appContext, File appData, File configDir) {
        List<File> targetSkillRoots = new ArrayList<>();
        // v2.34.0 global skills path: ~/.gemini/config/skills
        if (configDir != null) {
            targetSkillRoots.add(new File(configDir, "skills"));
        }
        addUniqueFile(targetSkillRoots, new File(appData, "skills"));
        File geminiDir = appData.getParentFile();
        if (geminiDir != null) {
            addUniqueFile(targetSkillRoots, new File(new File(geminiDir, "antigravity"), "skills"));
        }

        // Primary source: APK assets/runtime/seed/skills/**
        int seededFromAssets = 0;
        try {
            seededFromAssets = copySkillAssetsRecursive(
                    appContext.getAssets(),
                    RuntimePaths.SEED_ASSET + "/skills",
                    "",
                    targetSkillRoots);
        } catch (Exception e) {
            Log.w(TAG, "failed seeding skills from assets: " + e.getMessage());
        }

        // Secondary fallback: overlay skills in <PREFIX>/etc/antigravity/skills/**
        int seededFromOverlay = 0;
        try {
            File overlaySkills = new File(EnvPaths.prefix(appContext), "etc/antigravity/skills");
            if (overlaySkills.isDirectory()) {
                seededFromOverlay = copySkillFilesRecursive(overlaySkills, "", targetSkillRoots);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed seeding skills from env overlay: " + e.getMessage());
        }

        if (seededFromAssets > 0 || seededFromOverlay > 0) {
            Log.i(TAG, "seeded bundled skills (assets=" + seededFromAssets
                    + ", overlay=" + seededFromOverlay + ")");
        }
    }

    private static void addUniqueFile(List<File> list, File candidate) {
        if (candidate == null) {
            return;
        }
        for (File existing : list) {
            if (existing.equals(candidate)) {
                return;
            }
        }
        list.add(candidate);
    }

    private static int copySkillAssetsRecursive(AssetManager assets, String assetPath,
                                                String relPath, List<File> targetRoots) {
        String[] children;
        try {
            children = assets.list(assetPath);
        } catch (Exception e) {
            return 0;
        }
        if (children != null && children.length > 0) {
            int count = 0;
            for (String child : children) {
                String nextAsset = assetPath + "/" + child;
                String nextRel = relPath.isEmpty() ? child : relPath + "/" + child;
                count += copySkillAssetsRecursive(assets, nextAsset, nextRel, targetRoots);
            }
            return count;
        }
        if (relPath.isEmpty()) {
            return 0;
        }

        byte[] content = null;
        int written = 0;
        for (File root : targetRoots) {
            File target = new File(root, relPath);
            if (target.isFile() && target.length() > 0L) {
                continue;
            }
            try {
                if (content == null) {
                    try (InputStream in = assets.open(assetPath)) {
                        content = IoUtil.readAllBytes(in);
                    }
                }
                if (writeIfParentReady(target, content)) {
                    written++;
                }
            } catch (Exception e) {
                Log.d(TAG, "skipping asset skill entry " + assetPath + ": " + e.getMessage());
            }
        }
        return written;
    }

    private static int copySkillFilesRecursive(File srcDir, String relPath, List<File> targetRoots) {
        File[] children = srcDir.listFiles();
        if (children == null || children.length == 0) {
            return 0;
        }
        int written = 0;
        for (File child : children) {
            String nextRel = relPath.isEmpty() ? child.getName() : relPath + "/" + child.getName();
            if (child.isDirectory()) {
                written += copySkillFilesRecursive(child, nextRel, targetRoots);
            } else if (child.isFile()) {
                byte[] content = null;
                for (File root : targetRoots) {
                    File target = new File(root, nextRel);
                    if (target.isFile() && target.length() > 0L) {
                        continue;
                    }
                    try {
                        if (content == null) {
                            try (FileInputStream in = new FileInputStream(child)) {
                                content = IoUtil.readAllBytes(in);
                            }
                        }
                        if (writeIfParentReady(target, content)) {
                            written++;
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "failed copying overlay skill " + child + ": " + e.getMessage());
                    }
                }
            }
        }
        return written;
    }

    private static boolean writeIfParentReady(File target, byte[] bytes) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            return false;
        }
        if (tmp.renameTo(target)) {
            return true;
        }
        if (target.delete() && tmp.renameTo(target)) {
            return true;
        }
        IoUtil.deleteQuietly(tmp);
        return false;
    }

    private static void seedStateFile(Context appContext, String assetName, File target)
            throws Exception {
        if (target.isFile() && target.length() > 0) {
            Log.i(TAG, "state present; left untouched: " + target);
            return;
        }
        try (InputStream in = appContext.getAssets().open(
                RuntimePaths.SEED_ASSET + "/" + assetName)) {
            String content = IoUtil.readAll(in, "UTF-8");
            AtomicFileWriter.writeText(target, content);
        }
        Log.i(TAG, "seeded onboarding state (was missing): " + target);
    }
}

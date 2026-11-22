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
 * wizard is skipped, and non-destructively seeds default Android MCP server
 * configuration and bundled agent skills. Idempotent and non-destructive:
 * never overwrites existing user files or user-configured MCP servers.
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";
    private static final String ANDROID_MCP_SERVER_KEY = "android-device";

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
            File configDir = projects.getParentFile();
            if (configDir != null && (configDir.isDirectory() || configDir.mkdirs())) {
                seedStateFile(appContext, "AGENTS.md", new File(configDir, "AGENTS.md"));
            }

            // Non-destructively register the bundled android-device stdio MCP server
            seedMcpConfigs(appContext, appData);

            // Non-destructively copy bundled Antigravity skills
            seedSkills(appContext, appData);

            // The project store resolves its projects here; without the
            // pseudo-project file it logs project_store_get_file_missing.
            if (!projects.exists() && !projects.mkdirs()) {
                Log.w(TAG, "cannot create " + projects);
                return;
            }
            File outside = new File(projects, "outside-of-project.json");
            if (!outside.isFile()) {
                AtomicFileWriter.writeText(outside, "{}\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "onboarding seed failed (continuing): " + e.getMessage());
        }
    }

    private static void seedMcpConfigs(Context appContext, File appData) {
        File filesDir = appContext.getFilesDir();
        File prefix = EnvPaths.prefix(appContext);
        File mcpBin = new File(prefix, "bin/agy-mcp-android");

        List<File> targets = new ArrayList<>();
        targets.add(new File(appData, "mcp_config.json"));
        File geminiDir = appData.getParentFile();
        if (geminiDir != null) {
            File altDir = new File(geminiDir, "antigravity");
            if (!altDir.equals(appData)) {
                targets.add(new File(altDir, "mcp_config.json"));
            }
        }

        for (File target : targets) {
            try {
                mergeAndroidDeviceMcp(target, mcpBin.getAbsolutePath(),
                        filesDir.getAbsolutePath(), prefix.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "failed seeding mcp_config at " + target + ": " + e.getMessage());
            }
        }
    }

    private static void mergeAndroidDeviceMcp(File target, String mcpCommand,
                                              String homePath, String prefixPath) throws Exception {
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

        if (mcpServers.has(ANDROID_MCP_SERVER_KEY)) {
            Log.d(TAG, "mcp server '" + ANDROID_MCP_SERVER_KEY + "' already present in " + target);
            return;
        }

        JSONObject serverSpec = new JSONObject();
        serverSpec.put("command", mcpCommand);
        serverSpec.put("args", new JSONArray());
        JSONObject envObj = new JSONObject();
        envObj.put("HOME", homePath);
        envObj.put("PREFIX", prefixPath);
        serverSpec.put("env", envObj);

        mcpServers.put(ANDROID_MCP_SERVER_KEY, serverSpec);
        AtomicFileWriter.writeText(target, root.toString(2) + "\n");
        Log.i(TAG, "seeded '" + ANDROID_MCP_SERVER_KEY + "' MCP server into " + target);
    }

    private static void seedSkills(Context appContext, File appData) {
        List<File> targetSkillRoots = new ArrayList<>();
        targetSkillRoots.add(new File(appData, "skills"));
        File geminiDir = appData.getParentFile();
        if (geminiDir != null) {
            File altDir = new File(geminiDir, "antigravity");
            if (!altDir.equals(appData)) {
                targetSkillRoots.add(new File(altDir, "skills"));
            }
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

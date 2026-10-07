package com.agy.install;

import android.content.res.AssetManager;

import com.agy.util.FileModeUtil;
import com.agy.util.ShellQuote;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Installs the bundled pkg wrapper under libexec/pkg and a bin/pkg shim. */
public final class PkgInstaller {

    private PkgInstaller() {
    }

    public static void install(AssetManager assets, File prefix) {
        File libexec = new File(prefix, "libexec/pkg");
        if (!libexec.isDirectory() && !libexec.mkdirs() && !libexec.isDirectory()) {
            throw new IllegalStateException("cannot create " + libexec);
        }
        OverlayCopier.copyTree(assets, EnvPaths.ASSET_PKG, libexec);
        File script = new File(libexec, "pkg");
        if (!script.isFile()) {
            throw new IllegalStateException("required asset missing: " + EnvPaths.ASSET_PKG + "/pkg");
        }
        FileModeUtil.setExec(script);
        writeWrapper(new File(prefix, "bin/pkg"), prefix);
    }

    private static void writeWrapper(File wrapper, File prefix) {
        File bin = wrapper.getParentFile();
        if (bin != null && !bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            throw new IllegalStateException("cannot create " + bin);
        }
        String bash = new File(prefix, "bin/bash").getAbsolutePath();
        String script = new File(prefix, "libexec/pkg/pkg").getAbsolutePath();
        StringBuilder sb = new StringBuilder();
        sb.append('#').append('!').append(bash).append('\n');
        sb.append("export PREFIX=").append(ShellQuote.quote(prefix.getAbsolutePath())).append('\n');
        sb.append("exec ").append(ShellQuote.quote(script)).append(" \"$@\"").append('\n');
        try (OutputStream out = new FileOutputStream(wrapper)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + wrapper + ": " + e.getMessage(), e);
        }
        FileModeUtil.setExec(wrapper);
    }
}

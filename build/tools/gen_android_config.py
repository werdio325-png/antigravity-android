#!/usr/bin/env python3
"""Generate Android build constants from config/app.env.

Usage: gen_android_config.py <app.env> <outdir>

Writes:
  <outdir>/<pkg path>/BuildConfig.java   (PORT, PORT_DEV, CORE_VERSION,
                                           OAUTH_SCHEME, APP_NAME, MIN_API)
  <outdir>/res/values/gen.xml            (app_name, https_server_port,
                                           oauth_scheme)

Used by build/phases/00-dev.sh (and consumed by 06-rjava.sh). Plain KEY=VALUE
parsing: no shell logic in the env file, no interpolation.
"""
import html
import os
import sys


def parse_env(path):
    data = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            data[key.strip()] = value.strip()
    return data


def java_string(value):
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return '"' + escaped + '"'


def main(argv):
    if len(argv) != 3:
        sys.stderr.write("usage: gen_android_config.py <app.env> <outdir>\n")
        return 2

    env_path, outdir = argv[1], argv[2]
    env = parse_env(env_path)

    pkg = env.get("PKG", "com.agy")
    port = env.get("PORT", "0")
    port_dev = env.get("PORT_DEV", "0")
    core_version = env.get("CORE_VERSION", "")
    oauth_scheme = env.get("OAUTH_SCHEME", "")
    app_name = env.get("APP_NAME", "")
    min_api = env.get("MIN_API", "24")

    pkg_dir = os.path.join(outdir, *pkg.split("."))
    res_values_dir = os.path.join(outdir, "res", "values")
    os.makedirs(pkg_dir, exist_ok=True)
    os.makedirs(res_values_dir, exist_ok=True)

    build_config_path = os.path.join(pkg_dir, "BuildConfig.java")
    with open(build_config_path, "w", encoding="utf-8") as f:
        f.write("package %s;\n\n" % pkg)
        f.write("public final class BuildConfig {\n")
        f.write("    private BuildConfig() {}\n\n")
        f.write("    public static final int PORT=%s;\n" % port)
        f.write("    public static final int PORT_DEV=%s;\n" % port_dev)
        f.write("    public static final String CORE_VERSION=%s;\n" % java_string(core_version))
        f.write("    public static final String OAUTH_SCHEME=%s;\n" % java_string(oauth_scheme))
        f.write("    public static final String APP_NAME=%s;\n" % java_string(app_name))
        f.write("    public static final int MIN_API=%s;\n" % min_api)
        f.write("}\n")

    gen_xml_path = os.path.join(res_values_dir, "gen.xml")
    with open(gen_xml_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write("<resources>\n")
        f.write('    <string name="app_name">%s</string>\n' % html.escape(app_name, quote=True))
        f.write('    <integer name="https_server_port">%s</integer>\n' % port)
        f.write('    <string name="oauth_scheme">%s</string>\n' % html.escape(oauth_scheme, quote=True))
        f.write("</resources>\n")

    print("[gen_android_config] %s" % build_config_path)
    print("[gen_android_config] %s" % gen_xml_path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

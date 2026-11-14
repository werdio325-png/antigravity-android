#!/usr/bin/env python3
"""
01_gen_config.py - Generates BuildConfig.java, gen.xml, and dev-aware AndroidManifest.xml.

All package identifiers come from config/app.env (PKG / PKG_DEV); nothing here
hardcodes an application id, so the fork can be rebranded by editing one file.
"""
import sys
import os
import html
import re

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))


def parse_env(path):
    data = {}
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                data[key.strip()] = value.strip().strip('"').strip("'")
    return data


def java_string(value):
    escaped = str(value).replace("\\", "\\\\").replace('"', '\\"')
    return '"' + escaped + '"'


def main():
    build_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PROJECT_ROOT, "build")
    env_file = os.path.join(PROJECT_ROOT, "config", "app.env")
    env = parse_env(env_file)

    is_dev = os.environ.get("DEV_MODE") == "1"

    pkg_base = env.get("PKG")
    if not pkg_base:
        raise SystemExit("[ERROR] config/app.env is missing PKG")
    pkg_dev = env.get("PKG_DEV", pkg_base + ".dev")
    app_id = pkg_dev if is_dev else pkg_base
    port = env.get("PORT_DEV", "45158") if is_dev else env.get("PORT", "45157")
    port_dev = env.get("PORT_DEV", "45158")
    gw_port = env.get("GATEWAY_PORT_DEV", "45161") if is_dev else env.get("GATEWAY_PORT", "45160")
    core_version = env.get("CORE_VERSION", "2.11.0")
    app_version_name = env.get("APP_VERSION_NAME", "3.0.0")
    app_version_code = env.get("APP_VERSION_CODE", "30")
    oauth_scheme = env.get("OAUTH_SCHEME", "antigravity")
    app_name = env.get("APP_NAME_DEV", "Continuum Dev") if is_dev else env.get("APP_NAME", "Continuum")
    min_api = env.get("MIN_API", "24")
    target_sdk = env.get("TARGET_SDK", "28")
    update_repo = env.get("UPDATE_REPO", "")
    update_asset = env.get("UPDATE_ASSET", "continuum.apk")
    update_sums_asset = env.get("UPDATE_CHECKSUMS_ASSET", "SHA256SUMS.txt")
    is_debug = "true" if is_dev else "false"

    pkg_dir = os.path.join(build_dir, "gen", *pkg_base.split("."))
    res_values_dir = os.path.join(build_dir, "res", "values")
    os.makedirs(pkg_dir, exist_ok=True)
    os.makedirs(res_values_dir, exist_ok=True)

    # 1. BuildConfig.java (always in the base package for source compatibility,
    #    even in dev builds whose manifest package is PKG_DEV).
    build_config_path = os.path.join(pkg_dir, "BuildConfig.java")
    with open(build_config_path, "w", encoding="utf-8") as f:
        f.write("package %s;\n\n" % pkg_base)
        f.write("public final class BuildConfig {\n")
        f.write("    private BuildConfig() {}\n\n")
        f.write("    public static final String APPLICATION_ID = %s;\n" % java_string(app_id))
        f.write("    public static final String BASE_PACKAGE = %s;\n" % java_string(pkg_base))
        f.write("    public static final int PORT = %s;\n" % port)
        f.write("    public static final int PORT_DEV = %s;\n" % port_dev)
        f.write("    public static final int GATEWAY_PORT = %s;\n" % gw_port)
        f.write("    public static final String CORE_VERSION = %s;\n" % java_string(core_version))
        f.write("    public static final String APP_VERSION_NAME = %s;\n" % java_string(app_version_name))
        f.write("    public static final int APP_VERSION_CODE = %s;\n" % app_version_code)
        f.write("    public static final String OAUTH_SCHEME = %s;\n" % java_string(oauth_scheme))
        f.write("    public static final String APP_NAME = %s;\n" % java_string(app_name))
        f.write("    public static final int MIN_API = %s;\n" % min_api)
        f.write("    public static final int TARGET_SDK = %s;\n" % target_sdk)
        f.write("    public static final boolean DEBUG = %s;\n" % is_debug)
        f.write("    public static final String UPDATE_REPO = %s;\n" % java_string(update_repo))
        f.write("    public static final String UPDATE_ASSET = %s;\n" % java_string(update_asset))
        f.write("    public static final String UPDATE_CHECKSUMS_ASSET = %s;\n" % java_string(update_sums_asset))
        f.write("}\n")

    # 2. gen.xml
    gen_xml_path = os.path.join(res_values_dir, "gen.xml")
    with open(gen_xml_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write("<resources>\n")
        f.write('    <string name="app_name">%s</string>\n' % html.escape(app_name, quote=True))
        f.write('    <integer name="https_server_port">%s</integer>\n' % port)
        f.write('    <integer name="gateway_port">%s</integer>\n' % gw_port)
        f.write('    <string name="oauth_scheme">%s</string>\n' % html.escape(oauth_scheme, quote=True))
        f.write("</resources>\n")

    # 3. AndroidManifest.xml (dev-aware, package injected from app.env)
    src_manifest = os.path.join(PROJECT_ROOT, "app", "src", "main", "AndroidManifest.xml")
    target_manifest = os.path.join(build_dir, "AndroidManifest.xml")
    with open(src_manifest, "r", encoding="utf-8") as f:
        content = f.read()

    content = content.replace('${applicationId}', app_id)
    content = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{app_version_name}"', content)
    content = re.sub(r'android:versionCode="[^"]+"', f'android:versionCode="{app_version_code}"', content)

    if is_dev:
        content = content.replace(f'package="{pkg_base}"', f'package="{pkg_dev}"')
        content = content.replace(f'{pkg_base}.permission.INTERNAL_BROADCAST', f'{pkg_dev}.permission.INTERNAL_BROADCAST')
        content = content.replace('<application', '<application android:debuggable="true"', 1)
        print(f"[*] Configured DEV manifest: package={pkg_dev}, debuggable=true")

    with open(target_manifest, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"[OK] Generated BuildConfig.java -> {build_config_path}")
    print(f"[OK] Generated gen.xml -> {gen_xml_path}")
    print(f"[OK] Generated manifest -> {target_manifest}")


if __name__ == "__main__":
    main()

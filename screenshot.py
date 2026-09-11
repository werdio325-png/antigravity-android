#!/usr/bin/env python3
import sys, os, subprocess, struct, re, zlib

PKG = "com.antigravity.standalone"
OUT_PNG = "/storage/emulated/0/Documents/Antigravity/screenshot.png"
if len(sys.argv) > 1:
    OUT_PNG = sys.argv[1]

def write_png(filename, width, height, raw_rgba):
    lines = []
    stride = width * 4
    for y in range(height):
        lines.append(b'\x00' + raw_rgba[y * stride : (y + 1) * stride])
    raw_data = b''.join(lines)
    compressed = zlib.compress(raw_data)

    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))

    png = (
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
        + chunk(b'IDAT', compressed)
        + chunk(b'IEND', b'')
    )
    with open(filename, 'wb') as f:
        f.write(png)

def get_window_bounds():
    try:
        out = subprocess.check_output(['rish', '-c', 'dumpsys activity activities'], text=True, timeout=5)
        lines = out.splitlines()
        found_pkg = False
        for line in lines:
            if PKG in line and 'Task{' in line:
                found_pkg = True
                continue
            if found_pkg:
                m = re.search(r'bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', line)
                if m:
                    return [int(x) for x in m.groups()]
                m2 = re.search(r'mBounds=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)', line)
                if m2:
                    return [int(x) for x in m2.groups()]
                if 'Task{' in line:
                    break
    except Exception as e:
        print(f"[-] Could not query task bounds: {e}")
    return None

def main():
    print("📸 Захват экрана через Shizuku...")
    p = subprocess.run(['rish', '-c', 'screencap'], stdout=subprocess.PIPE, check=True)
    data = p.stdout
    if len(data) < 12:
        print("❌ Ошибка screencap: пустой вывод")
        sys.exit(1)

    scr_w, scr_h, fmt = struct.unpack('<III', data[:12])
    raw_pixels = data[12:]
    expected_len = scr_w * scr_h * 4
    if len(raw_pixels) < expected_len:
        print(f"❌ Ошибка screencap: получено {len(raw_pixels)} байт, ожидалось {expected_len}")
        sys.exit(1)

    bounds = get_window_bounds()
    if bounds:
        l, t, r, b = bounds
        print(f"🔍 Найдено окно {PKG}: [{l}, {t}] -> [{r}, {b}]")
        # Clamp to screen
        l = max(0, min(l, scr_w))
        r = max(0, min(r, scr_w))
        t = max(0, min(t, scr_h))
        b = max(0, min(b, scr_h))
        crop_w = r - l
        crop_h = b - t

        if crop_w > 10 and crop_h > 10 and (crop_w < scr_w or crop_h < scr_h):
            print(f"✂️ Обрезка до границ оконного режима: {crop_w}x{crop_h} px...")
            cropped_lines = []
            stride = scr_w * 4
            for y in range(t, b):
                start = y * stride + (l * 4)
                end = start + (crop_w * 4)
                cropped_lines.append(raw_pixels[start:end])
            crop_rgba = b''.join(cropped_lines)
            write_png(OUT_PNG, crop_w, crop_h, crop_rgba)
            print(f"✅ Скриншот окна сохранён: {OUT_PNG} ({crop_w}x{crop_h})")
            return

    # Fallback to full screen
    print(f"🖼 Захват полного экрана: {scr_w}x{scr_h} px...")
    write_png(OUT_PNG, scr_w, scr_h, raw_pixels[:expected_len])
    print(f"✅ Полный скриншот сохранён: {OUT_PNG} ({scr_w}x{scr_h})")

if __name__ == "__main__":
    main()

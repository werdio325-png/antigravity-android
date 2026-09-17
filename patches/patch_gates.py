import os
import re
import sys

def patch_eligibility(target_path):
    """
    Снятие регионального экрана проверки (eligibility / gate screen) для ARM64.
    1. CLI gate: ldrb w1, [x0, #8] -> mov w1, #1
    2. Manager auth gate (hasValidAuth):
       ldrb w3, [x0, #8] ; tbz w3, #0, ... -> mov w3, #1 ; strb w3, [x0, #8]
    """
    with open(target_path, "rb") as f:
        data = bytearray(f.read())

    count = 0

    # 1. CLI Gate
    sig_cli = re.compile(rb"(\x01\x20\x40\x39)(.{3}\x37)", re.S)
    for m in sig_cli.finditer(data):
        offset = m.start()
        data[offset:offset+4] = b"\x21\x00\x80\x52" # mov w1, #1
        count += 1

    # 2. Manager Auth Gate (hasValidAuth)
    _ARM64_TBZ_W3_BIT0 = rb"[\x03\x23\x43\x63\x83\xa3\xc3\xe3]..\x36"
    _ARM64_TOKEN_SETUP = rb"(?:....){1,2}\x03\x10\x06\xa9"
    sig_mgr = re.compile(rb"\x03\x20\x40\x39" + _ARM64_TBZ_W3_BIT0 + _ARM64_TOKEN_SETUP, re.S)
    
    for m in sig_mgr.finditer(data):
        offset = m.start()
        # mov w3, #1 ; strb w3, [x0, #8]
        data[offset:offset+8] = b"\x23\x00\x80\x52\x03\x20\x00\x39"
        count += 1

    if count == 0:
        # Проверяем, может уже пропатчен
        patched_cli = re.compile(rb"\x21\x00\x80\x52.{3}\x37", re.S)
        patched_mgr = re.compile(rb"\x23\x00\x80\x52\x03\x20\x00\x39" + _ARM64_TOKEN_SETUP, re.S)
        if list(patched_cli.finditer(data)) or list(patched_mgr.finditer(data)):
            print("[patch_gates] Binary is already patched.")
            return True
        print("[patch_gates] Warning: Gate signatures not found.")
        return False

    with open(target_path, "wb") as f:
        f.write(data)

    print(f"[patch_gates] Successfully patched {count} gate(s).")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 patch_gates.py <binary_path>")
        sys.exit(1)
    patch_eligibility(sys.argv[1])

"""Android-seccomp syscall retarget patch.

Remove Android-seccomp-trapped syscalls from the Antigravity arm64 Go core and
from every embedded Go helper ELF.

How targets are found (no fixed offsets):

  * ELF64-LE AArch64 header magic scan finds every embedded ELF.
  * Each ELF's `.text` comes from its own section headers (base relative).
  * The Go pclntab (magic 0xfffffff1 / 0xfffffff0 / 0xfffffff2) is located
    inside the ELF's region and parsed.
  * `syscall.faccessat2` / `syscall.fchmodat2` are resolved through the
    pclntab; the MOVZ is then matched **only inside the function body**.

A naive global MOVZ scan is forbidden: it has protobuf/data false positives
(439 appears 6 times and 452 appears 5 times in the fresh core).

Fail-closed: a present target that cannot be unambiguously patched (0 or >1
MOVZ inside the body, or an unparsable pclntab) aborts without writing.
"""

import sys

from patch_lib import cli as _cli
from patch_lib import elf, module_base as mb, scan
from groups.syscalls import signatures as sig

GROUP_ID = "seccomp"
GROUP = "seccomp"
FILE = "syscalls"
DESCRIPTION = "Retarget faccessat2(439)->faccessat(48) and fchmodat2(452)->fchmodat(53)."
TAG = "patch_syscalls"


def run(target_path, verify=False, dry_run=False, allow_missing=False, log=print):
    report = mb.new_report(
        GROUP_ID, GROUP, FILE, DESCRIPTION,
        expected={"main": {"faccessat2": 1, "fchmodat2": 1},
                  "helper_go_elfs": {"faccessat2": 1}},
        elves=[], offsets={})

    data = mb.read_target(target_path, report)
    if data is None:
        return False, report

    bases = elf.find_elf_headers(data)
    if not bases:
        report["messages"].append("no valid AArch64 ELF found")
        return False, report
    if 0 not in bases:
        report["messages"].append("primary ELF at offset 0 not detected")
        return False, report

    go_elves = 0
    to_patch = 0
    changed = 0
    ok = True

    for idx, base in enumerate(bases):
        region_end = bases[idx + 1] if idx + 1 < len(bases) else len(data)
        entry = {"elf_base": hex(base), "region_end": hex(region_end), "is_primary": base == 0}
        text = elf.parse_text_section(data, base)
        if text is None:
            entry["kind"] = "non-go (no .text)"
            report["elves"].append(entry)
            continue
        text_off, text_sz = text
        entry["text"] = {"offset": hex(text_off), "size": hex(text_sz)}
        pcln = scan.find_pclntab(data, base, region_end)
        if pcln is None:
            entry["kind"] = "elf, no Go pclntab (skipped)"
            report["elves"].append(entry)
            continue

        go_elves += 1
        entry["kind"] = "go"
        entry["pclntab"] = {"offset": hex(pcln["pcln_base"]), "nfunc": pcln["nfunc"]}
        report["messages"].append(
            "go elf @%s: .text@%s pclntab@%s nfunc=%d"
            % (entry["elf_base"], hex(text_off), hex(pcln["pcln_base"]), pcln["nfunc"]))

        # Sanity: pclntab parser must resolve a known runtime symbol.
        if scan.find_function(data, pcln, sig.KNOWN_FUNC) is None:
            entry["error"] = "pclntab parser failed to resolve %s" % sig.KNOWN_FUNC
            report["messages"].append("ERROR: %s" % entry["error"])
            ok = False
            report["elves"].append(entry)
            continue

        entry["functions"] = {}
        for fname, old_imm, new_imm in sig.TARGETS:
            found = scan.find_function(data, pcln, fname)
            if found is None:
                entry["functions"][fname] = {"present": False}
                if base == 0 or fname == "syscall.faccessat2":
                    msg = "missing %s in %s" % (fname, entry["elf_base"])
                    if allow_missing:
                        entry["functions"][fname]["state"] = "missing-allowed"
                        report["messages"].append("SKIP: " + msg)
                    else:
                        entry["functions"][fname]["state"] = "missing"
                        report["messages"].append("ERROR: " + msg)
                        ok = False
                else:
                    entry["functions"][fname]["state"] = "absent"
                continue

            entry_off, size = found
            if size <= 0:
                entry["functions"][fname] = {"present": True, "error": "no size"}
                report["messages"].append("ERROR: %s has no resolvable size" % fname)
                ok = False
                continue
            start = text_off + entry_off
            end = start + size
            if end > len(data):
                entry["functions"][fname] = {"present": True, "error": "body out of file"}
                report["messages"].append("ERROR: %s body out of file" % fname)
                ok = False
                continue
            body = bytes(data[start:end])
            status, index, c_old, c_new = scan._patch_body(body, old_imm, new_imm)
            info = {
                "present": True,
                "entry": hex(start),
                "size": size,
                "old_count": c_old,
                "new_count": c_new,
                "state": status,
            }
            if status == "patch":
                file_off = start + index
                info["mov_offset"] = hex(file_off)
                report["offsets"].setdefault(
                    "elf_%s" % entry["elf_base"], {})[fname] = hex(file_off)
                to_patch += 1
                if not verify and not dry_run:
                    data[file_off:file_off + 4] = scan.movz_x0(new_imm)
                    changed += 1
                entry["functions"][fname] = info
                report["messages"].append(
                    "%s @%s: %s %d -> %d" % (
                        fname, hex(file_off),
                        "patched" if (changed and not verify and not dry_run) else "would patch",
                        old_imm, new_imm))
            elif status == "already":
                entry["functions"][fname] = info
                report["messages"].append("%s: already patched (%d)" % (fname, new_imm))
            else:
                entry["functions"][fname] = info
                report["messages"].append(
                    "ERROR: %s ambiguous/empty (old=%d new=%d)" % (fname, c_old, c_new))
                ok = False

        report["elves"].append(entry)

    if go_elves == 0:
        report["messages"].append("no Go ELF (with pclntab) found")
        ok = False

    if not ok:
        report["state"] = "error"
        return False, report

    if verify:
        # ensure every patch target is in the patched state
        v_ok = True
        for entry in report["elves"]:
            if entry.get("kind") != "go":
                continue
            for fname, info in entry.get("functions", {}).items():
                if not info.get("present"):
                    continue
                exp_old, exp_new = (
                    (sig.FACCESSAT2, sig.FACCESSAT) if fname == "syscall.faccessat2"
                    else (sig.FCHMODAT2, sig.FCHMODAT))
                if info["old_count"] != 0 or info["new_count"] < 1:
                    v_ok = False
        report["state"] = "verified" if v_ok else "mismatch"
        report["messages"].append("verify " + ("OK" if v_ok else "FAIL"))
        return v_ok, report

    if to_patch:
        if dry_run:
            report["state"] = "dry-run"
            report["changed"] = 0
            report["would_change"] = to_patch
        else:
            with open(target_path, "wb") as f:
                f.write(data)
            report["state"] = "patched"
            report["changed"] = changed
    else:
        report["state"] = "already"
        report["changed"] = 0
    return True, report


if __name__ == "__main__":
    sys.exit(_cli.main(run, as_json=True))

#!/usr/bin/env python3
"""
catalog.py - Derives the model catalogue from an Antigravity engine binary.

The engine (language_server) is a Go binary whose string table contains the model
identifiers it can route and the labels it shows for them. This module reads them
out of the binary instead of keeping a hardcoded model list in the Android app,
so the fork follows whatever the embedded engine actually supports.

Extraction is data-driven and two-phase:
  A. collect ids that stand on their own in the string table;
  B. recover ids that Go's linker packed against a neighbouring string
     ("...-lowgemini-3.8-flash-high...") using the families discovered in A.
A candidate is accepted only when the engine also ships a human label for it
("Gemini 4 Argon"), confirmed in one scan with strict label boundaries so that
"Gemini 3.1" is not accepted merely because "Gemini 3.1 Pro" exists.

Tiers come from the engine's own "-low/-medium/-high" ids and "(Low)" labels.

What this module never does is claim a model is *available*: availability is
decided by Google's servers for the signed-in account (entitlement, rollout,
region) and is only known from the engine's own responses at request time.
"""
import re
from typing import Dict, Any, List, Tuple

TIER_SUFFIXES = ("low", "medium", "high")
_MAX_SEGMENTS = 6

# Phase A: an id standing alone (not a fragment of a longer id / URL / anchor).
_STANDALONE = re.compile(
    rb"(?<![a-z0-9A-Z#/_\-.])([a-z][a-z0-9]{1,12})-(\d[a-z0-9.]*(?:-[a-z0-9.]+){0,5})"
)

# A label must not continue as a longer word/version: "Gemini 3.1" followed by
# " Pro" or ".5" is a prefix, not a label of its own. The check is applied per
# match because Go packs neighbouring strings without separators.
def _label_is_complete(data: bytes, end: int, label: str) -> bool:
    if label.endswith(")"):
        return True
    nxt = data[end:end + 2]
    if len(nxt) == 0:
        return True
    first = nxt[0]
    if 97 <= first <= 122 or 48 <= first <= 57:
        return False
    if first == 32 and len(nxt) > 1 and (65 <= nxt[1] <= 90 or 97 <= nxt[1] <= 122 or 48 <= nxt[1] <= 57):
        return False
    return True


def display_form(candidate: str) -> str:
    """The human spelling the engine uses for an id ("Gemini 4 Argon")."""
    parts = candidate.split("-")
    return " ".join([parts[0].capitalize()] + [p.capitalize() for p in parts[1:]])


def _extend_raw(data: bytes, start: int) -> str:
    """Read the lower-case model-id-shaped run starting at `start` (bounded)."""
    end = start
    limit = min(len(data), start + 64)
    while end < limit and (97 <= data[end] <= 122 or 48 <= data[end] <= 57 or data[end] in (45, 46)):
        end += 1
    return data[start:end].decode("ascii", "ignore")


def _confirm_labels(data: bytes, candidates: List[str]) -> set:
    """One scan over the binary; returns all labels that really appear."""
    labels = set()
    for raw in candidates:
        parts = raw.split("-")[:_MAX_SEGMENTS]
        for n in range(2, len(parts) + 1):
            prefix = parts[:n]
            if parts[n - 1] in TIER_SUFFIXES and n > 2:
                labels.add("%s (%s)" % (display_form("-".join(parts[:n - 1])), parts[n - 1].capitalize()))
                continue
            if "." in parts[1] or len(prefix) >= 3:
                labels.add(display_form("-".join(prefix)))
    if not labels:
        return set()
    ordered = sorted(labels, key=len, reverse=True)
    pattern = re.compile(b"|".join(re.escape(label.encode()) for label in ordered))
    found = set()
    for match in pattern.finditer(data):
        label = match.group(0).decode("ascii", "ignore")
        if label in found:
            continue
        if _label_is_complete(data, match.end(), label):
            found.add(label)
    return found


def _parse_base(raw: str, window: bytes, confirmed: set) -> str:
    """Longest prefix of a raw run that the engine labels for humans."""
    def labelled(candidate: str) -> bool:
        label = display_form(candidate)
        return label in confirmed or label.encode() in window

    parts = raw.split("-")[:_MAX_SEGMENTS]
    for n in range(len(parts), 1, -1):
        if parts[n - 1] in TIER_SUFFIXES and n > 2:
            base = "-".join(parts[:n - 1])
            if labelled(base):
                return base
        prefix = "-".join(parts[:n])
        if labelled(prefix):
            return prefix
    return ""


def extract_models(data: bytes) -> List[Dict[str, Any]]:
    """
    Return the catalogue embedded in an engine binary.

    Entry: {id, base_id, display_name, family, variant, tiers, engine_ids}.
    Tier variants are folded into the base entry, e.g. gemini-4-argon-low is a
    tier of base gemini-4-argon.
    """
    runs: Dict[str, bytes] = {}
    families = set()

    for match in _STANDALONE.finditer(data):
        raw = (match.group(1) + b"-" + match.group(2)).decode("ascii", "ignore")
        if raw in runs:
            continue
        runs[raw] = data[max(0, match.start() - 4096): match.end() + 4096]
        families.add(raw.split("-")[0])

    if families:
        family_re = b"|".join(re.escape(f.encode()) for f in sorted(families, key=len, reverse=True))
        anchored = re.compile(rb"(?:" + family_re + rb")-\d[a-z0-9.]*")
        for match in anchored.finditer(data):
            raw = _extend_raw(data, match.start())
            if not raw or raw in runs:
                continue
            runs[raw] = data[max(0, match.start() - 4096): match.start() + 64 + 4096]

    confirmed = _confirm_labels(data, list(runs.keys()))
    bases: Dict[str, Dict[str, Any]] = {}

    def record(base_id: str, tier: str) -> None:
        segs = base_id.split("-")
        if not ("." in segs[1] or len(segs) >= 3 or tier):
            return
        display = display_form(base_id)
        entry = bases.setdefault(base_id, {
            "id": base_id,
            "base_id": base_id,
            "display_name": display,
            "family": segs[0],
            "variant": "base",
            "tiers": [],
            "engine_ids": [base_id],
        })
        if tier and tier not in entry["tiers"]:
            entry["tiers"].append(tier)
        if tier:
            tier_id = base_id + "-" + tier
            if tier_id not in entry["engine_ids"]:
                entry["engine_ids"].append(tier_id)
            bases.setdefault(tier_id, {
                "id": tier_id,
                "base_id": base_id,
                "display_name": "%s (%s)" % (display, tier.capitalize()),
                "family": segs[0],
                "variant": "tier",
                "tiers": [tier],
                "engine_ids": [tier_id],
            })

    for raw, window in runs.items():
        base = _parse_base(raw, window, confirmed)
        if not base:
            continue
        record(base, "")
        # Tier coverage is read directly from the engine's own tier ids/labels,
        # which are frequently packed against neighbouring strings.
        for tier in TIER_SUFFIXES:
            tier_id = (base + "-" + tier).encode()
            tier_label = "%s (%s)" % (display_form(base), tier.capitalize())
            if tier_id in data or tier_label in confirmed or tier_label.encode() in window:
                record(base, tier)

    order = {t: i for i, t in enumerate(TIER_SUFFIXES)}
    for entry in bases.values():
        entry["tiers"] = sorted(entry["tiers"], key=lambda t: order.get(t, 99))

    # A bare version ("gemini-3.5") is a family marker, not a model, when the
    # engine also labels a longer id with the same prefix ("gemini-3.5-flash").
    base_ids = {b for b, e in bases.items() if e["variant"] == "base"}
    for base in sorted(base_ids):
        if any(other != base and other.startswith(base + "-") for other in base_ids):
            for key in [k for k, e in bases.items() if e["base_id"] == base]:
                bases.pop(key, None)
    return sorted(bases.values(), key=lambda m: (m["family"], m["base_id"],
                                                 order.get((m["tiers"] or [None])[0], 99), m["id"]))


def find_model(models: List[Dict[str, Any]], name: str) -> Dict[str, Any]:
    """Resolve a UI label or id to a catalogue entry (None when unknown)."""
    if not name:
        return None
    target = re.sub(r"[^a-z0-9]", "", name.lower())
    for entry in models:
        for candidate in (entry["id"], entry["display_name"]):
            if re.sub(r"[^a-z0-9]", "", candidate.lower()) == target:
                return entry
    for entry in models:
        base = re.sub(r"[^a-z0-9]", "", entry["base_id"].lower())
        if target.startswith(base) and target != base:
            return entry
    return None

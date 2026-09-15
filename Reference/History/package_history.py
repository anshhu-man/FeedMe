#!/usr/bin/env python3
"""Audit pinned historical archives and publish home-path-sanitized copies.

This deliberately does not extract ZIP entries, modify the source archives, or
silently remove rejected content. A failed audit stops publication.
"""

import argparse
import copy
import hashlib
import html
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import urllib.parse
import zipfile


SOURCES = (
    (
        "outputs/BiteClub_GenZ_UI_Kit.zip",
        "97149c06087fad13ca958ddb797e012d13d8e765fa657c05960bbff8113505b5",
        {"biteclub_ui", "biteclub_blueprint"},
    ),
    (
        "outputs/BiteClub_Production_Blueprint.zip",
        "002cd8e07de47301b08c7ff283018c5e8bf06d7c385a5d73cb866b32c58b3cca",
        {"biteclub_blueprint"},
    ),
)
TEXT_SUFFIXES = {
    ".html", ".md", ".json", ".mjs", ".kt", ".css", ".js", ".mmd", ".svg", ".csv",
}
DENIED_PARTS = {
    "node_modules", "build", ".gradle", ".kotlin", ".git", "__pycache__",
    ".ssh", ".aws", ".azure", ".gnupg", "xcuserdata",
}
DENIED_NAMES = {
    "credentials", "credentials.json", "credentials.xml", "secrets.json",
    "secrets.yaml", "secrets.yml", "local.properties", "google-services.json",
    "googleservice-info.plist", "id_rsa", "id_ecdsa", "id_ed25519", ".netrc",
    ".npmrc", ".pypirc", "authorized_keys", "known_hosts",
}
TOKEN_RULES = {
    "private-key-header": rb"-----BEGIN (?:[A-Z ]* )?PRIVATE KEY-----",
    "aws-access-key": rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "github-token": rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b",
    "openai-shaped-token": rb"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}\b",
    "google-api-key": rb"\bAIza[0-9A-Za-z_-]{35}\b",
    "slack-token": rb"\bxox[baprs]-[0-9A-Za-z-]{20,}\b",
    "jwt-shaped-token": rb"\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b",
    "literal-bearer-token": rb"(?i)\bBearer[ \t]+[A-Za-z0-9_+./=-]{32,}",
    "credential-bearing-url": rb"(?i)https?://[^\s/:<>\"']+:[^\s/@<>\"']+@",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def decoded_views(text):
    """Expose common encodings for audit, never rewrite via these lossy views."""
    result = [text]
    for _ in range(3):
        value = html.unescape(urllib.parse.unquote(result[-1]))
        value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m[1], 16)), value)
        value = value.replace(r"\/", "/")
        if value == result[-1]:
            break
        result.append(value)
    return result


def audit_tokens(data, entry):
    for name, pattern in TOKEN_RULES.items():
        require(re.search(pattern, data) is None, f"Token audit rejected {entry}: {name}")


def audit_path(info, allowed_roots):
    name = info.filename
    require(name and len(name.encode("utf-8")) <= 1024, "Invalid ZIP entry name length")
    require("\x00" not in name and "\\" not in name and not name.startswith("/"),
            f"Unsafe ZIP entry path: {name!r}")
    parts = name.removesuffix("/").split("/")
    require(all(p not in ("", ".", "..") and ":" not in p for p in parts),
            f"Unsafe ZIP entry path: {name!r}")
    require(parts[0] in allowed_roots, f"Unexpected archive root: {parts[0]}")
    lower = [p.lower() for p in parts]
    require(not DENIED_PARTS.intersection(lower), f"Excluded generated/private directory: {name}")
    base = lower[-1]
    require(base not in DENIED_NAMES and not base.startswith(".env")
            and not re.search(r"(?:service[-_]account|credentials?[-_](?:prod|live|service))", base)
            and PurePosixPath(base).suffix not in {
                ".key", ".pem", ".jks", ".keystore", ".p12", ".pfx", ".mobileprovision",
                ".apk", ".aar", ".jar", ".class", ".pyc", ".o", ".dylib", ".so",
            }, f"Credential/build filename rejected: {name}")
    mode = info.external_attr >> 16
    kind = stat.S_IFMT(mode)
    require(kind == (stat.S_IFDIR if info.is_dir() else stat.S_IFREG),
            f"Non-regular entry or symlink: {name}")
    require(not (info.flag_bits & 1), f"Encrypted ZIP entry: {name}")
    require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
            f"Unsupported ZIP compression: {name}")
    require(not info.comment, f"Unaudited ZIP entry comment: {name}")
    # These pinned archives carry only modification time and numeric Unix IDs.
    position = 0
    while position < len(info.extra):
        require(position + 4 <= len(info.extra), f"Malformed ZIP metadata: {name}")
        tag, size = struct.unpack_from("<HH", info.extra, position)
        position += 4
        require(tag in (0x5455, 0x7875) and position + size <= len(info.extra),
                f"Unaudited ZIP metadata: {name}")
        position += size


def audit_png(data, entry, private_home):
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), f"Invalid PNG signature: {entry}")
    position = 8
    kinds = []
    while position < len(data):
        require(position + 12 <= len(data), f"Truncated PNG: {entry}")
        size = struct.unpack_from(">I", data, position)[0]
        kind = data[position + 4:position + 8]
        require(position + 12 + size <= len(data), f"Truncated PNG chunk: {entry}")
        # caBX is existing signed content-provenance metadata, not a private key.
        # Unknown or compressed textual metadata is not silently bypassed.
        require(kind in (b"IHDR", b"IDAT", b"IEND", b"caBX"),
                f"Unaudited PNG metadata chunk: {entry}")
        kinds.append(kind)
        position += size + 12
    require(kinds and kinds[0] == b"IHDR" and kinds[-1] == b"IEND",
            f"Invalid PNG structure: {entry}")
    for view in decoded_views(data.decode("latin-1")):
        require(private_home.lower() not in view.lower(), f"Private home in binary asset: {entry}")
        audit_tokens(view.encode("utf-8"), entry)
    return sorted({kind.decode("ascii") for kind in kinds})


def sanitize(text, private_home, entry):
    target = "/Users/LOCAL_USER"
    variants = [
        (private_home.replace("/", r"\/"), target.replace("/", r"\/"), "escaped-slash"),
        (private_home.replace("/", r"\u002f"), target.replace("/", r"\u002f"), "unicode-slash"),
    ]
    for safe in ("", "/"):
        encoded = urllib.parse.quote(private_home, safe=safe)
        replacement = urllib.parse.quote(target, safe=safe)
        variants.append((urllib.parse.quote(encoded, safe=""),
                         urllib.parse.quote(replacement, safe=""), "double-percent-encoded"))
        variants.append((encoded, replacement, "percent-encoded"))
    variants.append((private_home, target, "plain"))
    # De-duplicate forms that do not contain an encoded character.
    unique = {}
    for before, after, label in variants:
        unique[before] = (after, label)
    counts = {}
    for before, (after, label) in unique.items():
        text, count = re.subn(re.escape(before), lambda _: after, text, flags=re.IGNORECASE)
        if count:
            counts[label] = counts.get(label, 0) + count
    for view in decoded_views(text):
        require(private_home.lower() not in view.lower(), f"Unredacted encoded home path: {entry}")
        require(re.search(r"/" r"Users/(?!LOCAL_USER(?:[/\s\"'<>]|$))[^/\s\"'<>]+", view) is None,
                f"Another personal home prefix requires review: {entry}")
        audit_tokens(view.encode("utf-8"), entry)
    return text, counts


def package(source_root, relative, pinned_hash, allowed_roots, private_home):
    original = source_root / relative
    require(original.is_file() and not original.is_symlink(), f"Missing/unsafe source: {relative}")
    raw = original.read_bytes()
    require(sha(raw) == pinned_hash, f"Original archive hash mismatch: {relative}")
    expected_contents = {}
    redactions = []
    png_chunks = set()
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw)) as source:
        entries = source.infolist()
        require(not source.comment and 0 < len(entries) <= 1000, f"Unaudited archive metadata: {relative}")
        require(sum(i.file_size for i in entries) <= 100 * 1024 * 1024, "Archive size exceeds audit bound")
        require(source.testzip() is None, f"Source CRC check failed: {relative}")
        names = set()
        folded_names = set()
        with zipfile.ZipFile(output, "w") as published:
            for entry in entries:
                audit_path(entry, allowed_roots)
                require(entry.filename not in names and entry.filename.casefold() not in folded_names,
                        f"Duplicate/case-colliding ZIP entry: {entry.filename}")
                names.add(entry.filename)
                folded_names.add(entry.filename.casefold())
                require(entry.file_size <= 16 * 1024 * 1024, f"Oversized entry: {entry.filename}")
                data = source.read(entry)
                audit_tokens(data, entry.filename)
                transformed = data
                if entry.is_dir():
                    require(not data, f"Directory has payload: {entry.filename}")
                else:
                    suffix = PurePosixPath(entry.filename).suffix
                    if suffix in TEXT_SUFFIXES:
                        text = data.decode("utf-8", errors="strict")
                        require("\x00" not in text, f"NUL in textual entry: {entry.filename}")
                        text, counts = sanitize(text, private_home, entry.filename)
                        transformed = text.encode("utf-8")
                        if transformed != data:
                            redactions.append({
                                "entry": entry.filename,
                                "replacementCounts": counts,
                                "originalSha256": sha(data),
                                "publishedSha256": sha(transformed),
                            })
                    else:
                        require(suffix == ".png", f"Unaudited file type: {entry.filename}")
                        png_chunks.update(audit_png(data, entry.filename, private_home))
                expected_contents[entry.filename] = transformed
                published.writestr(copy.copy(entry), transformed)
    result = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(result)) as verify:
        require(verify.namelist() == [i.filename for i in entries], "Repacked entry identity/order mismatch")
        require(verify.testzip() is None, "Repacked CRC check failed")
        for entry in verify.infolist():
            require(verify.read(entry) == expected_contents[entry.filename], "Repacked content mismatch")
    require(original.read_bytes() == raw, f"Source changed during packaging: {relative}")
    files = sum(not i.is_dir() for i in entries)
    filename = Path(relative).stem + "-public-sanitized.zip"
    return filename, result, {
        "original": {"relativePath": relative, "sha256": pinned_hash, "bytes": len(raw)},
        "published": {"filename": filename, "sha256": sha(result), "bytes": len(result)},
        "entryCount": len(entries),
        "fileCount": files,
        "directoryCount": len(entries) - files,
        "originalUncompressedFileBytes": sum(i.file_size for i in entries if not i.is_dir()),
        "publishedUncompressedFileBytes": sum(len(b) for n, b in expected_contents.items() if not n.endswith("/")),
        "topLevelDirectories": sorted(allowed_roots),
        "unchangedFileCount": files - len(redactions),
        "redactedRelativeEntries": redactions,
        "pngChunkTypes": sorted(png_chunks),
        "entryOrderNamesAndOtherContentPreserved": True,
        "sourceUnchangedAfterPackaging": True,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--private-home", required=True, help="Original home prefix; never recorded in output")
    args = parser.parse_args()
    require(re.fullmatch(r"/" r"Users/[^/\s]+", args.private_home) is not None
            and args.private_home != "/Users/LOCAL_USER", "Expected one non-placeholder macOS home prefix")
    destination = Path(__file__).resolve().parent
    packages = [package(args.source_root, *source, args.private_home) for source in SOURCES]
    manifest = {
        "schemaVersion": 1,
        "purpose": "Public-sanitized historical BiteClub archives; not current FeedMe implementation or verification evidence.",
        "sanitization": "Only personal home path prefixes in UTF-8 entry contents are replaced with /Users/LOCAL_USER; other entry contents are unchanged.",
        "archiveBytesAreNotOriginal": True,
        "audit": {
            "passed": True,
            "checks": [
                "pinned original SHA-256 and CRC", "path traversal and duplicate/case-collision rejection",
                "regular files/directories only; no symlinks or encrypted entries",
                "credential filenames and generated/build directories rejected",
                "bounded entry counts and uncompressed sizes", "strict UTF-8 text and known PNG metadata types",
                "raw and commonly decoded high-confidence token pattern scan",
                "plain/escaped/percent-encoded personal home prefix audit",
                "repacked CRC, exact entry identity/order and per-entry expected-content comparison",
                "original bytes rechecked after packaging",
            ],
            "tokenRuleNames": list(TOKEN_RULES),
            "limitations": "Pattern checks are bounded publication hygiene, not a guarantee that arbitrary prose or image pixels contain no sensitive information. Existing PNG content-provenance caBX metadata is preserved. Archives are historical design snapshots, not security attestations.",
        },
        "archives": [item[2] for item in packages],
    }
    payloads = [(name, data) for name, data, _ in packages]
    payloads.append(("HISTORY_MANIFEST.json", (json.dumps(manifest, indent=2) + "\n").encode("utf-8")))
    require(all(not (destination / name).exists() for name, _ in payloads),
            "Publication output already exists; review it rather than overwriting")
    for name, data in payloads:
        with (destination / name).open("xb") as output:
            output.write(data)
    print(json.dumps({"published": [item[2]["published"] for item in packages],
                      "redactedEntries": sum(len(item[2]["redactedRelativeEntries"]) for item in packages)}, indent=2))


if __name__ == "__main__":
    main()

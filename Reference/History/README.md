# Historical BiteClub archives

These are preserved **pre-FeedMe-name design snapshots**, not the current app,
current production contract, or current verification results. BiteClub is the
former name of FeedMe; the archive names and historical contents retain it.

Prefer the maintained [FeedMe UI kit](../../outputs/biteclub_ui/README.md),
[production blueprint](../../outputs/biteclub_blueprint/README.md), and
[application source](../../feedme/README.md). See the [Reference index](../README.md)
for the current idea, story, architecture, and design materials.

| Public archive | Historical contents |
| --- | --- |
| [BiteClub_GenZ_UI_Kit-public-sanitized.zip](BiteClub_GenZ_UI_Kit-public-sanitized.zip) | UI kit **and an older blueprint snapshot**; 314 files |
| [BiteClub_Production_Blueprint-public-sanitized.zip](BiteClub_Production_Blueprint-public-sanitized.zip) | Standalone blueprint snapshot; 187 files |

## Sanitization and integrity

The published ZIPs are intentionally **not byte-identical** to the private
original archives. Personal macOS home-directory prefixes in four textual
entries across the two ZIPs were replaced with `/Users/LOCAL_USER` (six
occurrences in total). All other file contents, entry names, and entry order are
preserved. No original archives or original verification evidence were edited.

[HISTORY_MANIFEST.json](HISTORY_MANIFEST.json) records each original and public
archive's SHA-256, byte size, entry/file counts, and the relative entries changed,
including before/after content hashes. This is publication provenance, not a
claim that a historical verification result applies to the sanitized copies.

The packaging audit rejects unsafe paths, symlinks, duplicate names, credential
files, generated/build directories, and high-confidence token patterns. It also
checks encoded home paths, CRCs, and every repacked entry's expected content.
These bounded checks are not a guarantee about arbitrary prose or image pixels.
Existing signed image-provenance metadata is retained; illustrative food images
are not clinical or nutritional evidence.

## Reproduce from the original archives

The standard-library-only [packaging script](package_history.py) accepts the
private source location at runtime; it does not record that location in its
outputs. Original archive hashes are pinned. It refuses to overwrite an existing
publication. To reproduce, copy the script into a new empty output directory and
run:

```sh
python3 package_history.py \
  --source-root /path/to/original/workspace \
  --private-home "$HOME"
```

The source workspace must contain the two original ZIPs under `outputs/`.
Repacking may vary between Python/zlib versions; the checked-in manifest records
the actual public bytes, while per-entry validation preserves their content.

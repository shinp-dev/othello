# Edax source provenance and Android modifications

This directory contains Edax source from the official upstream repository:

- URL: `https://github.com/abulmo/edax-reversi`
- commit: `14f048c05ddfa385b6bf954a9c2905bbe677e9d3`
- describe at import: `v4.6-9-g14f048c`
- commit date: 2025-03-10
- license: GNU GPL version 3 (`upstream/LICENSE`)

Re-import procedure:

```text
git clone https://github.com/abulmo/edax-reversi.git
git -C edax-reversi checkout 14f048c05ddfa385b6bf954a9c2905bbe677e9d3
copy edax-reversi/src -> third_party/edax/upstream/src
copy edax-reversi/LICENSE and README.md -> third_party/edax/upstream
apply patches/android-embedding-safety.patch
```

`problem/`, binaries, evaluation data, opening books, and upstream Git metadata
are intentionally excluded.

`SHA256SUMS` records every vendored upstream file and the applied patch. Verify
it from this directory with `sha256sum --check SHA256SUMS` (or the equivalent
PowerShell `Get-FileHash` comparison) before a release.

## Modified upstream files

- `src/util.h`: route `fatal_error` to the Android bridge only when
  `EDAX_ANDROID_EMBEDDED` is defined; add an Android API 26 compatible aligned
  allocator hook.
- `src/util.c`: implement aligned allocation with `posix_memalign`, because the
  NDK does not expose C17 `aligned_alloc` at the app's minimum API level.
- `src/eval.c`: route a missing evaluation file through recoverable fatal-error
  handling instead of terminating the Android process with `exit`.
- `src/book.h`, `src/book.c`: return the actual opening-book load result and
  close files on validation failures so the app can reject unsupported or
  malformed user files.

The exact diff is stored in `patches/android-embedding-safety.patch`. App-owned
JNI and safety code is under `analysis/edax/src/main/cpp` and does not pretend to
be upstream Edax.

#!/usr/bin/env bash
# Lays the Android port over a checkout of upstream Keisho.
#   prepare-upstream.sh <android-branch checkout> <upstream checkout>
# Copies the Android-only files and applies android/patches/upstream.patch
# (the few lines changed in upstream's own files). Fails loudly if upstream
# moved those lines so far that the patch no longer fits.
set -euo pipefail
A="$(cd "$1" && pwd)"
U="$(cd "$2" && pwd)"

cp "$A/src/osd/android_glue.h" "$A/src/osd/android_glue.cpp" "$U/src/osd/"
rm -rf "$U/android"
cp -r "$A/android" "$U/android"

cd "$U"
PATCH="$A/android/patches/upstream.patch"
if git apply --check "$PATCH" 2>/dev/null; then
    git apply "$PATCH"
    echo "upstream.patch applied cleanly"
elif git apply --3way "$PATCH" && ! git diff --name-only --diff-filter=U | grep -q .; then
    echo "upstream.patch applied with a 3-way merge"
elif git checkout -- . 2>/dev/null; git reset -q 2>/dev/null; patch -p1 --fuzz=3 --no-backup-if-mismatch < "$PATCH"; then
    echo "upstream.patch applied with fuzz"
else
    echo "::error::android/patches/upstream.patch no longer applies to upstream Keisho"
    exit 1
fi

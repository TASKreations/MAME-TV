#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=ca185b19ad27d531a91c92fa456aabd951004fb8
checksum=f3cd728dc2c1da9fa5d6126a492dc73756162dcbf3302f797d9b21ad9dff9d97
mkdir -p .native
if [[ -f .native/mame2003-plus/.revision ]] && [[ $(cat .native/mame2003-plus/.revision) == "$revision" ]]; then
    exit 0
fi
archive=$(mktemp .native/core.XXXXXX.tar.gz)
staging=$(mktemp -d .native/extract.XXXXXX)
trap 'rm -f "$archive"; rm -rf "$staging"' EXIT
curl --fail --location --retry 2 "https://codeload.github.com/libretro/mame2003-plus-libretro/tar.gz/$revision" -o "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check -
tar -xzf "$archive" -C "$staging" --strip-components=1
printf '%s\n' "$revision" > "$staging/.revision"
if [[ -e .native/mame2003-plus ]]; then
    echo 'Existing core differs from the pinned revision; move it aside before preparing.' >&2
    exit 1
fi
mv "$staging" .native/mame2003-plus

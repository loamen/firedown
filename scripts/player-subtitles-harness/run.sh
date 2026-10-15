#!/bin/sh
# Player subtitles harness: the REAL PlayerSubtitles (copied from app/src,
# never re-typed) — the format of a subtitle file, the language its name
# declares, and which of a video's downloaded subtitles shows by itself.
# JDK only. Then MUTANTS that must fail: a subtitle that always "belongs"
# (an unrelated subtitle on a news page would show over the wrong video),
# a pick that ignores the phone's language, and a stem that keeps the
# language tag (no YouTube caption would ever match its video by name).
#
# usage: sh scripts/player-subtitles-harness/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SRC="$ROOT/app/src/main/java/com/solarized/firedown/phone/player/PlayerSubtitles.java"
BUILD="$HERE/build"

run_one() {
    name="$1"
    sedexpr="$2"
    out="$BUILD/$name"
    rm -rf "$out"
    mkdir -p "$out/gen/com/solarized/firedown/phone/player" "$out/classes"
    if [ -z "$sedexpr" ]; then
        cp "$SRC" "$out/gen/com/solarized/firedown/phone/player/"
    else
        sed "$sedexpr" "$SRC" > "$out/gen/com/solarized/firedown/phone/player/PlayerSubtitles.java"
        if cmp -s "$SRC" "$out/gen/com/solarized/firedown/phone/player/PlayerSubtitles.java"; then
            echo "mutant $name did not change the source" >&2
            exit 2
        fi
    fi
    javac -nowarn -encoding UTF-8 -d "$out/classes" $(find "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.PlayerSubtitlesHarness
}

echo "== real =="
run_one real ""
status=0

mutant() {
    echo
    echo "== mutant: $1 (must FAIL) =="
    if run_one "$1" "$2" > "$BUILD/$1.log" 2>&1; then
        echo "mutant $1 PASSED the harness — the checks have no teeth" >&2
        status=1
    else
        grep '^FAIL' "$BUILD/$1.log" | sed 's/^/  /'
        echo "  (failed as expected)"
    fi
}

mutant alwaysbelongs 's/if (candidate.linked || otherVideos == 0) {/if (true) {/'
mutant nodevice 's/boolean deviceMatch = language != null/boolean deviceMatch = false \&\& language != null/'
mutant stemkeepstag 's/name = LANGUAGE_TAG.matcher(name).replaceFirst("");//'
exit $status

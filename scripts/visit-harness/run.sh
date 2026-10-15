#!/bin/sh
# Visit harness: drives the REAL GeckoState visit-identity code (updateVisit,
# pageIdentityKey, isQueryRefinement and helpers — spliced out of app/src by
# splice.py, never copied by hand) through URL sequences and checks when the
# visit id moves. A moved id sends every capture stamped before it behind
# "Earlier in this tab" in the Captured sheet. JDK + python3 only; android.net.Uri
# and DebugLog are stubs.
#
# What it pins: a YouTube Mix moving index=1 -> index=2 on the SAME video keeps
# the visit (the 2026-10-15 report), while a position-only query, another
# video, another list, a hub -> item navigation and a v=A -> v=B change still
# move it, and the Instagram ?l=1 refinement still keeps it. Then two MUTANTS
# prove the checks bite: with no position params the Mix checks fail; without
# the "another parameter still names the item" guard the viewer check fails.
#
# usage: sh scripts/visit-harness/run.sh [-v]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
BUILD="$HERE/build"

run_one() {
    mutation="$1"
    shift
    out="$BUILD/$mutation"
    rm -rf "$out"
    mkdir -p "$out/gen" "$out/classes"
    if [ "$mutation" = real ]; then
        python3 "$HERE/splice.py" "$ROOT" "$out/gen"
    else
        python3 "$HERE/splice.py" "$ROOT" "$out/gen" "--mutate=$mutation"
    fi
    javac -nowarn -d "$out/classes" $(find "$HERE/stub" "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.VisitHarness "$@"
}

echo "== real =="
run_one real "$@"
status=0

for m in noposition noguard; do
    echo
    echo "== mutant: $m (must FAIL) =="
    if run_one "$m" > "$BUILD/$m.log" 2>&1; then
        echo "mutant $m PASSED the harness — the checks have no teeth" >&2
        status=1
    else
        grep '^FAIL' "$BUILD/$m.log" | sed 's/^/  /'
        echo "  (failed as expected)"
    fi
done
exit $status

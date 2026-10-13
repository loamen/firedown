#!/bin/sh
# Drives the REAL P2P share LAN plumbing on a plain JVM:
#   - P2pHttp (the request-head parser both tiny servers share): the exact
#     CRLFCRLF stop (a body right behind the head arrives byte-exact — the
#     stray-\n bug that once corrupted deflate codes), query/header parse,
#     the head cap, Content-Length edge cases.
#   - P2pLanServer (the receiver's /offer + the sender's /answer): token gate
#     (403), method/route gate (404, and the route the owner does not serve),
#     body cap (400), handler verdicts (204 vs 409), the declined body, and
#     that a 409'd client can retry with a different route.
#     Plus the connection deadline (a trickling client is cut, not served
#     forever), the bounded worker pool (others served meanwhile; one past
#     the pool dropped at once), the exact-bytes token compare, and one
#     winner among simultaneous offers.
#   - RelayCredsPolicy: a cached TURN credential is reused only with enough
#     life for the offer window + a transfer, and the still-valid fallback;
#     the network gate (no network / captive portal skip, "no internet"
#     still tries).
#   - P2pFileNames: a received file's name, bounded and correct without an
#     extension (the old loop spun forever on the shared disk executor).
#   - P2pSessionPolicy: attach / start / busy when a share screen opens.
#   - P2pLanServer.reachableHost: nearby lists IPv4 only.
#
#   sh scripts/p2p-harness/run.sh
#
# Real classes are COPIED from app/src at run time — never re-implemented.
# android.util.Log / BuildConfig / the annotations are stubs. Needs a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/p2pshare"
for f in P2pHttp P2pLanServer RelayCredsPolicy P2pFileNames P2pSessionPolicy; do
  cp "$ROOT/app/src/main/java/com/solarized/firedown/p2pshare/$f.java" "$OUT/src/com/solarized/firedown/p2pshare/"
done
javac -nowarn -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.p2pshare.P2pHarness

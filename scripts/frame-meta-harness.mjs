// frame-meta-harness.mjs — per-clip capture metadata across FRAMES, in a real
// browser. The REAL js/content-script.js runs in every frame of locally served
// pages (a stubbed `browser`), and the REAL pickCaptureMeta from requests.js
// ranks what it answers, so a check fails exactly when a capture would be
// misnamed on a device.
//
// Why a browser and not the smoke's vm: the bug this pins lives BETWEEN frames
// (a clip played in an iframe, declared by the page that embeds it — the
// firedown.app /demo showcase titled every clip "Firedown · demo"), and only a
// real DOM has frames, origins, base URLs and sandboxing.
//
// Engine caveat: this runs Chromium, where the script is a page script. On a
// device it is a Firefox content script reading through Xray wrappers; the
// same-origin parent read is the one behaviour to confirm there
// (adb logcat, or a firedown.app/demo capture showing per-clip names).
//
// Needs Playwright + its Chromium (`npm i -g playwright && npx playwright
// install chromium`); SKIPs cleanly without them.
//   node scripts/frame-meta-harness.mjs [path/to/content-script.js]
// The optional path runs another revision (proving the checks have teeth).
import { readFileSync } from "node:fs";
import http from "node:http";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ext = join(root, "app/src/main/assets/webrequests");
const csPath = process.argv[2] || join(ext, "js/content-script.js");
const cs = readFileSync(csPath, "utf8");

// pickCaptureMeta is a pure exported function; lift it out of the module
// source rather than import requests.js (which registers every listener).
const reqSrc = readFileSync(join(ext, "js/requests.js"), "utf8");
const pickSrc = reqSrc.match(/export function pickCaptureMeta\(meta\) \{[\s\S]*?\n\}\n/);
if (!pickSrc) {
  console.error("FAIL: pickCaptureMeta not found in requests.js");
  process.exit(1);
}
const pickCaptureMeta = new Function(`${pickSrc[0].replace(/^export /, "")}; return pickCaptureMeta;`)();

async function loadChromium() {
  try {
    return (await import("playwright")).chromium;
  } catch (_) { /* fall through to the global install */ }
  try {
    const globalRoot = execFileSync("npm", ["root", "-g"], { encoding: "utf8" }).trim();
    return createRequire(join(globalRoot, "noop.js"))("playwright").chromium;
  } catch (_) {
    return null;
  }
}

const chromium = await loadChromium();
if (!chromium) {
  console.log("SKIP: playwright not installed (npm i -g playwright)");
  process.exit(0);
}

// ── Pages, keyed by host + path. a.test and b.test are two ORIGINS on one
// server (Chromium maps *.test to 127.0.0.1). ──
const P = "__PORT__";
const A = `http://a.test:${P}`;
const B = `http://b.test:${P}`;
const block = (items) =>
  `<script type="application/firedown+json">${JSON.stringify(items)}</script>`;
const ld = (obj) => `<script type="application/ld+json">${JSON.stringify(obj)}</script>`;
const html = (title, head, body) =>
  `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>${head}</head><body>${body}</body></html>`;

const SITE = {
  // 1. The firedown.app shape: the homepage declares the clips, an iframe at
  //    /demo/index.html plays them through ../ paths. A malformed block comes
  //    first (one bad block must not hide the next); a path-relative
  //    VideoObject contentUrl belongs to the HOMEPAGE's base; an AudioObject
  //    declares the iframe's audio; a srcdoc child plays a declared clip too.
  [`a.test/home.html`]: html("Home",
    `<meta property="og:title" content="Home OG"><meta property="og:image" content="/og.png">`
    + `<script type="application/firedown+json">[{"contentUrl": oops</script>`
    + block([
      { contentUrl: "/assets/clips/a.mp4", name: "Clip A", thumbnail: "/assets/clips/a.jpg" },
      { contentUrl: "/assets/clips/own.mp4", name: "Parent Own" },
      { contentUrl: "/assets/clips/sd.mp4", name: "Clip SD" },
    ])
    + ld({ "@context": "https://schema.org", "@type": "VideoObject", name: "Clip B",
      contentUrl: "assets/clips/b.mp4", thumbnailUrl: "thumbs/b.jpg" })
    + ld({ "@context": "https://schema.org", "@type": "AudioObject", name: "Clip C",
      contentUrl: "/assets/clips/c.mp3" }),
    `<iframe src="demo/index.html" title="Demo"></iframe>`
    + `<iframe srcdoc="<video src='/assets/clips/sd.mp4'></video>"></iframe>`),
  [`a.test/demo/index.html`]: html("Demo frame",
    block([{ contentUrl: "../assets/clips/own.mp4", name: "Frame Own" }]),
    `<video src="../assets/clips/a.mp4" poster="../assets/clips/a-frame.jpg"></video>`
    + `<video src="../assets/clips/b.mp4"></video>`
    + `<video src="../assets/clips/own.mp4"></video>`
    + `<video src="../assets/clips/d.mp4"></video>`
    + `<audio src="../assets/clips/c.mp3"></audio>`
    + `<iframe src="inner.html"></iframe>`),
  [`a.test/demo/inner.html`]: html("Inner", "", `<video src="../assets/clips/a.mp4"></video>`),

  // 2. A cross-origin parent declaring this frame's clip: never read.
  [`b.test/xhome.html`]: html("X home",
    block([{ contentUrl: `${A}/assets/clips/x.mp4`, name: "Leak X" }])
    + ld({ "@type": "VideoObject", name: "Leak X LD", contentUrl: `${A}/assets/clips/x.mp4` }),
    `<iframe src="${A}/xframe.html"></iframe>`),
  [`a.test/xframe.html`]: html("X frame", "", `<video src="/assets/clips/x.mp4"></video>`),

  // 3. A same-origin top behind a CROSS-origin middle frame: the walk skips
  //    the middle (never reads its block) and still reaches the top.
  [`a.test/mhome.html`]: html("M home",
    block([{ contentUrl: "/assets/clips/m.mp4", name: "Clip M" }]),
    `<iframe src="${B}/middle.html"></iframe>`),
  [`b.test/middle.html`]: html("Middle",
    block([{ contentUrl: `${A}/assets/clips/m.mp4`, name: "Middle Says" }]),
    `<iframe src="${A}/minner.html"></iframe>`),
  [`a.test/minner.html`]: html("M inner", "", `<video src="/assets/clips/m.mp4"></video>`),

  // 4. A sandboxed (opaque-origin) frame of the SAME site: not the page's
  //    origin any more, so the parent's block is not its to read.
  [`a.test/shome.html`]: html("S home",
    block([{ contentUrl: "/assets/clips/s.mp4", name: "Leak S" }]),
    `<iframe sandbox="allow-scripts" src="/sframe.html"></iframe>`),
  [`a.test/sframe.html`]: html("S frame", "", `<video src="/assets/clips/s.mp4"></video>`),
};

const server = http.createServer((req, res) => {
  const host = (req.headers.host || "").split(":")[0];
  const body = SITE[`${host}${req.url.split("?")[0]}`];
  if (body == null) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(body.split(P).join(String(server.address().port)));
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;
const a = A.replace(P, port);
const b = B.replace(P, port);

// The extension's `browser`, per frame: the responder registers on
// onMessage, the scanner's batches land in __reports.
const STUB = `window.__listeners = []; window.__reports = [];
window.browser = { runtime: {
  sendNativeMessage: () => Promise.resolve(false),
  sendMessage: (m) => { window.__reports.push(m); return Promise.resolve(false); },
  onMessage: { addListener: (f) => window.__listeners.push(f) } },
  tabs: { sendMessage: () => Promise.resolve() } };
window.__ask = async (u) => {
  for (const f of window.__listeners) {
    const r = f({ kind: 'get-page-metadata', mediaUrl: u });
    if (r !== undefined) return await r;
  }
  return null;
};`;

let failures = 0;
let checks = 0;
const expect = (ok, label, detail) => {
  checks++;
  if (ok) {
    console.log(`  ok   ${label}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? `  (${detail})` : ""}`);
  }
};

let browser;
try {
  browser = await chromium.launch({
    args: ["--host-resolver-rules=MAP *.test 127.0.0.1", "--no-proxy-server"],
  });
} catch (e) {
  console.log(`SKIP: Chromium did not launch (${e.message.split("\n")[0]})`);
  server.close();
  process.exit(0);
}

// Load a page, inject the content script into every frame, let the scanner
// flush (BATCH_MS 200), return a helper bound to that page.
async function open(url) {
  const page = await (await browser.newContext()).newPage();
  await page.route("**/*", (r) =>
    new URL(r.request().url()).hostname.endsWith(".test") ? r.continue() : r.abort());
  await page.addInitScript(STUB);
  await page.goto(url, { waitUntil: "load" });
  for (const f of page.frames()) await f.addScriptTag({ content: cs });
  await page.waitForTimeout(500);
  const frame = (pathPart) => page.frames().find((f) =>
    pathPart === "srcdoc" ? f.url() === "about:srcdoc" : f.url().endsWith(pathPart));
  const ask = async (f, clip) => {
    const meta = await f.evaluate((u) => window.__ask(u), `${a}/assets/clips/${clip}`);
    return { meta, pick: meta ? pickCaptureMeta(meta) : null };
  };
  const scanned = async (f, clip) => {
    const reports = await f.evaluate(() => window.__reports.filter((r) => r.kind === "images-detected"));
    for (const r of reports) {
      const m = r.meta && r.meta[`${a}/assets/clips/${clip}`];
      if (m) return pickCaptureMeta(m);
    }
    return null;
  };
  return { page, frame, ask, scanned };
}

try {
  console.log(`content script: ${csPath}`);

  console.log("same-origin parent (the firedown.app /demo shape)");
  {
    const { frame, ask, scanned } = await open(`${a}/home.html`);
    const demo = frame("/demo/index.html");
    const inner = frame("/demo/inner.html");
    const top = frame("/home.html");

    let r = await ask(demo, "a.mp4");
    expect(r.pick?.name === "Clip A", "iframe clip named by the parent's firedown+json (wire query)", r.pick?.name);
    expect(r.meta?.videoLdMatchThumbnail === `${a}/assets/clips/a.jpg`,
      "declared relative thumbnail made absolute against the PARENT", r.meta?.videoLdMatchThumbnail);
    const s = await scanned(demo, "a.mp4");
    expect(s?.name === "Clip A", "the DOM-scan report carries the same name", s?.name);

    r = await ask(demo, "b.mp4");
    expect(r.pick?.name === "Clip B", "path-relative VideoObject contentUrl resolved against the parent's base", r.pick?.name);
    expect(r.pick?.img === `${a}/thumbs/b.jpg`, "VideoObject thumbnail absolute against the parent", r.pick?.img);

    r = await ask(demo, "c.mp3");
    expect(r.meta?.audioLdName === "Clip C" && r.meta?.audioRole === "content",
      "AudioObject in the parent names the iframe's audio", `${r.meta?.audioLdName}/${r.meta?.audioRole}`);

    r = await ask(demo, "own.mp4");
    expect(r.pick?.name === "Frame Own", "the frame's own declaration wins over the parent's", r.pick?.name);

    r = await ask(demo, "d.mp4");
    expect(r.pick?.name === "Demo frame", "an undeclared clip keeps the FRAME's title", r.pick?.name);
    expect(r.meta?.ogTitle === "" && r.meta?.ogImage === "",
      "the parent's og:title / og:image never cross frames", `${r.meta?.ogTitle}|${r.meta?.ogImage}`);

    r = await ask(inner, "a.mp4");
    expect(r.pick?.name === "Clip A", "two frames down still reaches the declaring page", r.pick?.name);

    r = await ask(top, "a.mp4");
    expect(r.pick?.name === "Clip A", "top frame: a malformed block does not hide the next one", r.pick?.name);

    r = await ask(frame("srcdoc"), "sd.mp4");
    expect(r.pick?.name === "Clip SD", "srcdoc child (inherits the origin) reads the parent", r.pick?.name);
  }

  console.log("cross-origin parent");
  {
    const { frame, ask } = await open(`${b}/xhome.html`);
    const r = await ask(frame("/xframe.html"), "x.mp4");
    expect(r.meta !== null, "the responder still answers (the cross-origin read did not throw out)");
    expect(r.pick?.name === "X frame", "a cross-origin parent's declarations are never read", r.pick?.name);
  }

  console.log("same-origin top behind a cross-origin middle frame");
  {
    const { frame, ask } = await open(`${a}/mhome.html`);
    const r = await ask(frame("/minner.html"), "m.mp4");
    expect(r.pick?.name === "Clip M", "the walk climbs past the middle and reads the top", r.pick?.name);
  }

  console.log("sandboxed (opaque-origin) frame");
  {
    const { frame, ask } = await open(`${a}/shome.html`);
    const r = await ask(frame("/sframe.html"), "s.mp4");
    expect(r.meta !== null && r.pick?.name === "S frame",
      "a sandboxed frame does not read its parent", r.pick?.name);
  }
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures ? 1 : 0);

// Smoke test for the p2pshare engine page script — run with:
//   node scripts/p2pshare-smoke.mjs
//
// Loads assets/p2pshare/engine-page.js in a vm context under a stubbed
// `window` (the engine is now a PAGE-WORLD script that talks to the bridge
// content script via window.postMessage, not a native port). Exercises the
// __init handshake, the soft bad-code path, the FDS1./FDR1. code codec, and
// the stop teardown. The WebRTC/DataChannel halves can only be proven
// on-device — this guards the protocol plumbing around them.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app/src/main/assets/p2pshare/engine-page.js"), "utf8");

let failures = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log(`ok    ${name}`);
  } else {
    failures++;
    console.log(`FAIL  ${name}${detail ? " — " + detail : ""}`);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ── stubs ──────────────────────────────────────────────────────────────────
const messageListeners = [];
const postedEvents = []; // engine "evt" payloads (what the bridge would relay)
let helloSeen = false;

const location = { origin: "http://127.0.0.1:53535", pathname: "/engine" };

const windowStub = {
  location,
  addEventListener: (type, fn) => {
    if (type === "message") { messageListeners.push(fn); }
  },
  postMessage: (msg) => {
    if (!msg) { return; }
    if (msg.p2p === "hello") { helloSeen = true; }
    else if (msg.p2p === "evt") { postedEvents.push(msg.data); }
  },
};

// Tune the engine's wall-clock timers to milliseconds for the watchdog
// checks below (read once by the engine at load, from window.__p2pTuning).
windowStub.__p2pTuning = {
  connectTimeoutMs: 400, ackTimeoutMs: 120, drainPollMs: 15,
  stallTimeoutMs: 150, lostTimeoutMs: 150, watchTickMs: 25,
};

const context = vm.createContext({
  window: windowStub,
  location,
  console,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  TextEncoder,
  TextDecoder,
  Blob,
  Response,
  CompressionStream,
  DecompressionStream,
  btoa: (text) => Buffer.from(text, "binary").toString("base64"),
  atob: (text) => Buffer.from(text, "base64").toString("binary"),
  // RTCPeerConnection deliberately ABSENT — the plumbing paths under test
  // don't construct one; the real thing only works on-device.
});

vm.runInContext(source, context, { filename: "engine-page.js" });

// Deliver a Java->page command exactly as the bridge would.
const sendCommand = (cmd) =>
  messageListeners.forEach((fn) => fn({ source: windowStub, data: { p2p: "cmd", data: cmd } }));

// ── load + __init handshake ─────────────────────────────────────────────────
check("engine says hello on load", helloSeen === true);
check("engine registered a message listener", messageListeners.length === 1);
check("no events before init", postedEvents.length === 0);

sendCommand({ type: "__init", debug: false });
check("__init triggers ready", postedEvents.length === 1 && postedEvents[0].type === "ready");

// ── bad-code handling (soft error, no session) ──────────────────────────────
postedEvents.length = 0;
sendCommand({ type: "recv-start", code: "garbage", stun: "" });
await sleep(50);
check("bad offer code -> bad-code error",
    postedEvents.length === 1 && postedEvents[0].type === "error" && postedEvents[0].code === "bad-code");

// ── offer-parsed carries the answer-return URLs (LAN + rendezvous) ─────────
postedEvents.length = 0;
const offerWithAns = await vm.runInContext(`
  encodeCode(OFFER_PREFIX, { v: 1, sdp: "v=0\\r\\n", name: "a.bin", size: 5,
      mime: "application/octet-stream", dev: "Test",
      ans: "http://192.168.1.2:40000/answer?t=aa",
      rvz: "https://api.firedown.app/v1/p2p/a/abc123" })
`, context);
sendCommand({ type: "recv-start", code: offerWithAns, stun: "" });
await sleep(50);
check("offer-parsed surfaces ans + rvz",
    postedEvents.length === 1 && postedEvents[0].type === "offer-parsed"
        && postedEvents[0].ans === "http://192.168.1.2:40000/answer?t=aa"
        && postedEvents[0].rvz === "https://api.firedown.app/v1/p2p/a/abc123"
        && postedEvents[0].name === "a.bin");
// An offer with neither field must surface EMPTY strings, not fail.
postedEvents.length = 0;
const offerNoAns = await vm.runInContext(`
  encodeCode(OFFER_PREFIX, { v: 1, sdp: "v=0\\r\\n", name: "b.bin", size: 5,
      mime: "application/octet-stream", dev: "Test" })
`, context);
sendCommand({ type: "recv-start", code: offerNoAns, stun: "" });
await sleep(50);
check("offer without ans/rvz -> empty strings",
    postedEvents.length === 1 && postedEvents[0].type === "offer-parsed"
        && postedEvents[0].ans === "" && postedEvents[0].rvz === "");
sendCommand({ type: "stop" });
await sleep(20);

// ── code codec round-trip ───────────────────────────────────────────────────
const codecProbe = `
  (async () => {
    const payload = { v: 1, sdp: "v=0\\r\\n" + "a=candidate".repeat(40), name: "clip.mp4", size: 12345, mime: "video/mp4", dev: "Pixel" };
    const code = await encodeCode(OFFER_PREFIX, payload);
    const back = await decodeCode(OFFER_PREFIX, code);
    return { code, back };
  })()
`;
const { code, back } = await vm.runInContext(codecProbe, context);
check("code carries the FDS1. prefix", code.startsWith("FDS1."));
check("code is QR-sized", code.length < 2500, `length ${code.length}`);
check("codec round-trips", JSON.stringify(back).includes('"clip.mp4"') && back.size === 12345);
const badDecode = await vm.runInContext(
    `decodeCode(ANSWER_PREFIX, ${JSON.stringify(code)}).then(() => "decoded", (e) => e.message)`, context);
check("wrong prefix rejected", badDecode === "bad-code");

// ── iceServers sanitizer: keep valid stun:/turn:, drop junk, preserve creds ──
// Java hands the engine a fully-formed RTCIceServer[]; sanitizeIceServers is
// the guard against a bad settings value breaking the ctor. Exercise it.
const iceClean = await vm.runInContext(`sanitizeIceServers([
  { urls: "stun:stun.cloudflare.com:3478" },
  { urls: ["turn:relay.example:80","turn:relay.example:443"], username: "user", credential: "pass" },
  { urls: "https://evil.example/not-ice" },
  { urls: "" },
  { nope: 1 },
  { urls: "turn:custom:3478", username: "u", credential: "p" }
])`, context);
check("ice sanitizer keeps only valid stun/turn entries",
    iceClean.length === 3
    && iceClean[0].urls[0] === "stun:stun.cloudflare.com:3478"
    && iceClean[1].urls.length === 2 && iceClean[1].username === "user"
    && iceClean[2].urls[0] === "turn:custom:3478" && iceClean[2].credential === "p");
check("ice sanitizer normalizes single url to array",
    Array.isArray(iceClean[0].urls) && iceClean[0].urls.length === 1);

// ── resume: answer-field gating (receiver side, pure) ───────────────────────
// The receiver only requests a resume from a sender that advertised res:1 —
// an old sender neither serves ranged reads nor sends "begin".
const gate = await vm.runInContext(`({
  ok:      resumeAnswerFields({ res: 1, size: 100 }, 50, "abc"),
  oldSend: resumeAnswerFields({ size: 100 }, 50, "abc"),
  zeroOff: resumeAnswerFields({ res: 1, size: 100 }, 0, "abc"),
  noTail:  resumeAnswerFields({ res: 1, size: 100 }, 50, ""),
})`, context);
check("resume fields gated on offer res:1",
    gate.ok && gate.ok.off === 50 && gate.ok.tail === "abc"
        && gate.oldSend === null && gate.zeroOff === null && gate.noTail === null);

// ── resume: requested-offset validation (sender side, pure) ─────────────────
const req = await vm.runInContext(`({
  ok:   requestedResumeOf({ off: 50, tail: "abc" }, 100),
  full: requestedResumeOf({ off: 100, tail: "abc" }, 100),
  big:  requestedResumeOf({ off: 101, tail: "abc" }, 100),
  neg:  requestedResumeOf({ off: -5, tail: "abc" }, 100),
  old:  requestedResumeOf({}, 100),
})`, context);
check("sender validates the requested offset",
    req.ok === 50 && req.full === 100 && req.big === 0 && req.neg === 0 && req.old === 0);

// ── resume: sender tail verification (real SHA-256, stubbed loopback) ───────
context.crypto = crypto; // node's WebCrypto — same digest the page gets
const tailBytes = new Uint8Array(64 * 1024).fill(7);
const tailHashB64 = Buffer.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", tailBytes)))
    .toString("base64url");
const readRequests = [];
context.fetch = async (url) => {
  readRequests.push(url);
  return { ok: true, arrayBuffer: async () => tailBytes.buffer.slice(0) };
};
const verified = await vm.runInContext(
    `verifyResumeTail("http://x/read?t=1", ${tailBytes.length}, ${JSON.stringify(tailHashB64)})`,
    context);
check("matching tail verifies", verified === true);
check("verify reads the exact tail window",
    readRequests.length === 1 && readRequests[0].endsWith("&from=0&len=" + tailBytes.length));
const mismatched = await vm.runInContext(
    `verifyResumeTail("http://x/read?t=1", ${tailBytes.length}, "AAAA")`, context);
check("wrong tail refuses the resume", mismatched === false);

// ── resume: receiver channel honors begin / restart / data-before-begin ─────
// bindReceiveChannel is driven directly with a fake DataChannel — the exact
// onmessage/flush machinery the wire feeds, minus WebRTC.
function fakeDc() {
  return { sent: [], bufferedAmount: 0, binaryType: "",
           send(m) { this.sent.push(m); }, close() {},
           addEventListener() {}, };
}
async function runReceive(resumeOff, events) {
  const writes = [];
  context.fetch = async (url, opts) => {
    writes.push({ url, size: opts && opts.body ? await opts.body.size : 0 });
    return { ok: true };
  };
  const dc = fakeDc();
  context.__dc = dc;
  await vm.runInContext(`
    session = { role: "receive", offer: { size: 100, res: 1 },
        resumeOff: ${resumeOff}, writeUrl: "http://x/write?t=1",
        progress: makeProgress(100), stopped: false };
    bindReceiveChannel(session, __dc);
  `, context);
  postedEvents.length = 0;
  for (const ev of events) {
    await vm.runInContext(`session && session.dc.onmessage(__ev)`,
        Object.assign(context, { __ev: ev }));
    await sleep(10);
  }
  await sleep(30);
  return { writes, dc, events: postedEvents.slice() };
}
const chunk = { data: new ArrayBuffer(50) };
const resumed = await runReceive(50, [
  { data: JSON.stringify({ t: "begin", off: 50 }) },
  chunk,
  { data: JSON.stringify({ t: "eof", bytes: 100 }) },
]);
check("resumed receive posts at the resume offset",
    resumed.writes.length === 1 && resumed.writes[0].url.endsWith("&off=50"));
check("resumed receive completes",
    resumed.events.some((e) => e.type === "done" && e.bytes === 100)
        && resumed.dc.sent.some((m) => m.includes("rcvd")));
const restarted = await runReceive(50, [
  { data: JSON.stringify({ t: "begin", off: 0 }) },
  { data: new ArrayBuffer(100) },
  { data: JSON.stringify({ t: "eof", bytes: 100 }) },
]);
check("refused resume restarts at offset 0",
    restarted.writes.length === 1 && restarted.writes[0].url.endsWith("&off=0")
        && restarted.events.some((e) => e.type === "done" && e.bytes === 100));
const blind = await runReceive(50, [chunk]);
check("data before begin fails the transfer",
    blind.events.some((e) => e.type === "error" && e.code === "transfer"));

// ── a pump parked on backpressure is released by closeSession ──────────────
// The channel never drains (bufferedAmount stays over the high-water mark and
// bufferedamountlow never fires — a dropped channel's shape), so pumpFile
// parks in sendChunk. closeSession must wake it: before, the pump stayed
// pending for the life of the engine page, holding its read chunk.
{
  let readCalls = 0;
  context.fetch = async () => ({
    ok: true,
    body: { getReader: () => ({
      read: async () => (readCalls++ === 0
          ? { value: new Uint8Array(256 * 1024), done: false }
          : { value: undefined, done: true }),
      cancel() {},
    }) },
  });
  context.__sendDc = { sent: 0, bufferedAmount: 1 << 30, send() { this.sent++; },
                       close() {}, addEventListener() {} };
  const pump = vm.runInContext(`
    session = { role: "send", size: 256 * 1024, readUrl: "http://x/read?t=1",
        progress: makeProgress(256 * 1024), stopped: false, dc: __sendDc };
    pumpFile(session);
  `, context);
  let settled = false;
  pump.then(() => { settled = true; });
  await sleep(30);
  check("pump parks on backpressure", !settled && context.__sendDc.sent === 1);
  vm.runInContext(`closeSession(session)`, context);
  await sleep(30);
  check("closeSession releases a parked pump", settled === true);
  check("released pump sends nothing more", context.__sendDc.sent === 1);
}

// ── ack timer arms only once the eof has DRAINED, then bounds the ack ──────
// The sender's eof is queued behind up to BUFFER_HIGH of data; arming the
// ack timer at the moment eof is QUEUED failed slow transfers at 100 % with
// "no delivery confirmation" while the bytes were still leaving the buffer.
{
  context.__ackDc = { bufferedAmount: 4096, sent: [], send(m) { this.sent.push(m); },
                      close() {}, addEventListener() {} };
  vm.runInContext(`
    session = { role: "send", size: 10, readUrl: "http://x/read?t=1",
        progress: makeProgress(10), stopped: false, acked: false, dc: __ackDc };
    armAckAfterDrain(session);
  `, context);
  postedEvents.length = 0;
  await sleep(300); // > 2 × ackTimeoutMs
  check("ack timer not armed while the eof is still buffered",
      postedEvents.length === 0 && vm.runInContext("session && session.awaitingAck !== true", context));
  context.__ackDc.bufferedAmount = 0;
  await sleep(60); // a few drain polls
  check("ack phase starts once the buffer drained",
      vm.runInContext("session && session.awaitingAck === true", context) && postedEvents.length === 0);
  await sleep(200); // > ackTimeoutMs
  check("drained + unacked fails honestly (transfer / no delivery confirmation)",
      postedEvents.some((e) => e.type === "error" && e.code === "transfer"
          && /no delivery confirmation/.test(e.detail))
      && vm.runInContext("session === null", context));
  // An ack landing inside the window disarms it.
  context.__ackDc.bufferedAmount = 0;
  vm.runInContext(`
    session = { role: "send", size: 10, readUrl: "http://x/read?t=1",
        progress: makeProgress(10), stopped: false, acked: false, dc: __ackDc };
    armAckAfterDrain(session);
  `, context);
  postedEvents.length = 0;
  await sleep(40);
  vm.runInContext("session.acked = true", context);
  await sleep(250);
  check("an ack inside the window disarms the timer", postedEvents.length === 0);
  vm.runInContext("closeSession(session)", context);
}

// ── stall watchdog (receiver) — flow resets it, silence fails it ──────────
{
  const writes = [];
  context.fetch = async (url, opts) => { writes.push(url); return { ok: true }; };
  const dc = fakeDc();
  context.__dc = dc;
  vm.runInContext(`
    session = { role: "receive", offer: { size: 1 << 20 }, resumeOff: 0,
        writeUrl: "http://x/write?t=1", progress: makeProgress(1 << 20), stopped: false };
    bindReceiveChannel(session, __dc);
  `, context);
  postedEvents.length = 0;
  // Steady flow for 2 × stallTimeoutMs: never stalls.
  for (let i = 0; i < 8; i++) {
    await vm.runInContext("session && session.dc.onmessage(__ev)",
        Object.assign(context, { __ev: { data: new ArrayBuffer(1024) } }));
    await sleep(40);
  }
  check("watchdog: steady flow never stalls",
      !postedEvents.some((e) => e.type === "error") && vm.runInContext("session !== null", context));
  vm.runInContext("__stalling = session", context);
  await sleep(260); // silence > stallTimeoutMs
  check("watchdog: no progress for STALL_TIMEOUT fails as stalled",
      postedEvents.some((e) => e.type === "error" && e.code === "stalled")
      && vm.runInContext("session === null", context));
  check("watchdog: the tick is cleared with the session",
      vm.runInContext("__stalling.stopped === true && __stalling.watchTimer === null", context));
}

// ── lost watchdog — a disconnected pc past LOST_TIMEOUT is "lost" ──────────
{
  context.__pc = { connectionState: "disconnected", addEventListener() {}, close() {} };
  vm.runInContext(`
    session = { role: "send", size: 100, progress: makeProgress(100), stopped: false,
        awaitingAck: true, pc: __pc, dc: null, disconnectedSince: Date.now() };
    startWatchdog(session);
  `, context);
  postedEvents.length = 0;
  await sleep(260);
  check("watchdog: disconnected past LOST_TIMEOUT fails as lost",
      postedEvents.some((e) => e.type === "error" && e.code === "lost")
      && vm.runInContext("session === null", context));
  // A connected pc in the ack phase: neither lost nor stalled.
  context.__pc = { connectionState: "connected", addEventListener() {}, close() {} };
  vm.runInContext(`
    session = { role: "send", size: 100, progress: makeProgress(100), stopped: false,
        awaitingAck: true, pc: __pc, dc: null, disconnectedSince: 0 };
    startWatchdog(session);
  `, context);
  postedEvents.length = 0;
  await sleep(260);
  check("watchdog: a connected pc awaiting the ack is left alone",
      postedEvents.length === 0 && vm.runInContext("session !== null", context));
  vm.runInContext("closeSession(session)", context);
  check("watchdog: closeSession drops the tick",
      vm.runInContext("session === null", context));
  postedEvents.length = 0;
  await sleep(200);
  check("watchdog: nothing fires after close", postedEvents.length === 0);
}

// ── "failed" after bytes moved is LOST, before any is NO-PATH ──────────────
{
  const listeners = {};
  context.__pc = { connectionState: "new",
      addEventListener(type, fn) { listeners[type] = fn; }, close() {} };
  vm.runInContext(`
    session = { role: "send", size: 100, progress: makeProgress(100), stopped: false,
        pc: __pc, flowStarted: true };
    watchConnection(session);
  `, context);
  postedEvents.length = 0;
  context.__pc.connectionState = "failed";
  listeners.connectionstatechange();
  check("connection failed after flow → lost",
      postedEvents.some((e) => e.type === "error" && e.code === "lost"));
  const listeners2 = {};
  context.__pc = { connectionState: "new",
      addEventListener(type, fn) { listeners2[type] = fn; }, close() {} };
  vm.runInContext(`
    session = { role: "send", size: 100, progress: makeProgress(100), stopped: false,
        pc: __pc, flowStarted: false };
    watchConnection(session);
  `, context);
  postedEvents.length = 0;
  context.__pc.connectionState = "failed";
  listeners2.connectionstatechange();
  check("connection failed before any flow → no-path",
      postedEvents.some((e) => e.type === "error" && e.code === "no-path"));
  // "connected" clears the disconnect clock the lost watchdog reads.
  const listeners3 = {};
  context.__pc = { connectionState: "disconnected",
      addEventListener(type, fn) { listeners3[type] = fn; }, close() {}, getStats: undefined };
  vm.runInContext(`
    session = { role: "send", size: 100, progress: makeProgress(100), stopped: false,
        pc: __pc, disconnectedSince: 0 };
    watchConnection(session);
  `, context);
  listeners3.connectionstatechange();
  const since = vm.runInContext("session.disconnectedSince", context);
  context.__pc.connectionState = "connected";
  listeners3.connectionstatechange();
  check("disconnected stamps the clock, connected clears it",
      since > 0 && vm.runInContext("session.disconnectedSince === 0", context));
  vm.runInContext("closeSession(session)", context);
}

// ── receiver backlog cap — unflushed bytes are bounded ──────────────────────
{
  let release;
  context.fetch = () => new Promise((resolve) => { release = () => resolve({ ok: true }); });
  const dc = fakeDc();
  context.__dc = dc;
  const cap = vm.runInContext("RECV_BACKLOG_MAX", context);
  vm.runInContext(`
    session = { role: "receive", offer: { size: 1e12 }, resumeOff: 0,
        writeUrl: "http://x/write?t=1", progress: makeProgress(1e12), stopped: false };
    bindReceiveChannel(session, __dc);
  `, context);
  postedEvents.length = 0;
  // A flush that never completes (the loopback POST is parked) keeps its
  // bytes counted as pending — simulate a parked backlog at the cap.
  vm.runInContext(`session.pendingBytes = ${cap}`, context);
  await vm.runInContext("session.dc.onmessage(__ev)",
      Object.assign(context, { __ev: { data: new ArrayBuffer(16) } }));
  check("backlog over RECV_BACKLOG_MAX fails the transfer",
      postedEvents.some((e) => e.type === "error" && e.code === "transfer" && /backlog/.test(e.detail)));
  // Accounting: a flushed batch leaves pendingBytes at 0.
  context.fetch = async () => ({ ok: true });
  vm.runInContext(`
    session = { role: "receive", offer: { size: 1e9 }, resumeOff: 0,
        writeUrl: "http://x/write?t=1", progress: makeProgress(1e9), stopped: false };
    bindReceiveChannel(session, __dc);
  `, context);
  await vm.runInContext("session.dc.onmessage(__ev)",
      Object.assign(context, { __ev: { data: new ArrayBuffer(64) } }));
  vm.runInContext("scheduleFlush(session, false)", context);
  const pendingDuring = vm.runInContext("session.pendingBytes", context);
  await sleep(20);
  check("pending bytes track a batch from schedule to POST completion",
      pendingDuring === 64 && vm.runInContext("session.pendingBytes === 0 && session.written === 64", context));
  vm.runInContext("closeSession(session)", context);
}

// ── wantsRelay: turn: and turns:, any case; never stun: ────────────────────
{
  const relay = vm.runInContext(`({
    turn: wantsRelay([{ urls: "turn:relay.example:3478" }]),
    turns: wantsRelay([{ urls: ["turns:relay.example:443?transport=tcp"] }]),
    upper: wantsRelay([{ urls: "TURN:relay.example" }]),
    stun: wantsRelay([{ urls: "stun:stun.example" }, { urls: ["stun:a", "stun:b"] }]),
    none: wantsRelay([]),
    junk: wantsRelay([{ urls: null }, {}, { urls: 7 }]),
  })`, context);
  check("wantsRelay sees turn: and turns: in any case, never stun:",
      relay.turn && relay.turns && relay.upper && !relay.stun && !relay.none && !relay.junk);
}

// ── stop is safe with no session ────────────────────────────────────────────
postedEvents.length = 0;
sendCommand({ type: "stop" });
check("stop answers closed",
    postedEvents.length === 1 && postedEvents[0].type === "state" && postedEvents[0].state === "closed");

process.exit(failures === 0 ? 0 : 1);

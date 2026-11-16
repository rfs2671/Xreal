'use strict';
// OFFLINE ANALYSIS ONLY. Runs XREAL's own web-updater SDK (downloaded from ota.xreal.com) against a
// FAKE HID device in this Node process. Nothing here talks to USB or to real glasses: the only
// "device" is the in-memory object below, which records every frame the vendor code sends.
const fs = require('fs');
const zlib = require('zlib');

const [bundlePath, ultraFwPath, air2FwPath, outPath] = process.argv.slice(2);
if (!bundlePath || !ultraFwPath || !air2FwPath) {
  console.error('usage: node vendor_sim.cjs <InfoBox bundle.js> <ultra_dp.bin> <air2_dp.bin> [out.json]');
  process.exit(2);
}
const bundle = fs.readFileSync(bundlePath, 'utf8');
const a0 = bundle.indexOf('var SDK={exports:{}};');
const a1 = bundle.indexOf('var SDKExports=SDK.exports;');
if (a0 < 0 || a1 < 0) throw new Error('SDK block not found in bundle');
const sdkSource = bundle.slice(a0, a1);

const MSG = { R_DP_VERSION: 22, R_SOC_VERSION: 38, R_ACTIVATION_TIME: 41, PREPARE: 110, START: 111, TRANSMIT: 112, FINISH: 113 };
const NAMES = Object.fromEntries(Object.entries(MSG).map(([k, v]) => [v, k]));
const ab = (buf) => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);

function crcOk(frame, len) {
  const stored = (frame[1] | (frame[2] << 8) | (frame[3] << 16) | (frame[4] << 24)) >>> 0;
  return zlib.crc32(frame.subarray(5, 5 + len)) === stored;
}

function response(reportLen, msgId, status, payload) {
  const b = new Uint8Array(reportLen);
  const len = 17 + 1 + payload.length;
  b[0] = 0xfd; b[5] = len & 255; b[6] = len >> 8; b[15] = msgId & 255; b[16] = msgId >> 8; b[22] = status;
  b.set(payload, 23);
  const crc = zlib.crc32(b.subarray(5, 5 + len));
  b[1] = crc & 255; b[2] = (crc >> 8) & 255; b[3] = (crc >> 16) & 255; b[4] = (crc >>> 24) & 255;
  return b;
}

// In-memory stand-in for a WebHID HIDDevice. `rule(info)` may return {status}, {drop:true} or {fail:true}.
class FakeHid extends EventTarget {
  constructor({ productId, reportLen, rule, versions }) {
    super();
    this.vendorId = 13080; this.productId = productId; this.productName = 'FAKE'; this.opened = false;
    this.reportLen = reportLen; this.rule = rule || (() => undefined); this.versions = versions;
    this.frames = []; this.counts = {}; this.afterFailure = 0; this.failed = false;
  }
  async open() { this.opened = true; }
  async close() { this.opened = false; }
  async sendReport(reportId, data) {
    const f = Uint8Array.from(data);
    const len = f[5] | (f[6] << 8);
    const msgId = f[15] | (f[16] << 8);
    const payload = f.slice(22, 22 + (len - 17));
    const n = (this.counts[msgId] = (this.counts[msgId] || 0) + 1);
    const info = { seq: this.frames.length, msgId, name: NAMES[msgId] || String(msgId), n, payloadLen: payload.length, reportBytes: f.length, reportId, magic: f[0], lenField: len, crcOk: crcOk(f, len) };
    if (this.failed) { this.afterFailure++; info.afterFailure = true; }
    this.frames.push({ info, payload, raw: f });
    const r = this.rule(info) || {};
    if (r.fail) { this.failed = true; throw new Error('simulated USB error (device removed)'); }
    if (r.drop) { this.failed = true; return; }
    const status = r.status === undefined ? 0 : r.status;
    if (status !== 0) this.failed = true;
    let pay = new Uint8Array(0);
    if (msgId === MSG.R_DP_VERSION) pay = Buffer.from(this.versions.dp);
    else if (msgId === MSG.R_SOC_VERSION) pay = Buffer.from(this.versions.soc);
    const resp = response(this.reportLen, msgId, status, pay);
    setTimeout(() => {
      const ev = new Event('inputreport');
      ev.device = this; ev.reportId = 0; ev.data = new DataView(resp.buffer);
      this.dispatchEvent(ev);
      if (typeof this.oninputreport === 'function') this.oninputreport(ev); // the Air-family transport uses the property form
    }, 0);
  }
}

function loadSdk(devices, progress) {
  const doc = { getElementById: (id) => ({ set innerText(v) { progress.push(id + '=' + v); } }) };
  const nav = { hid: { getDevices: async () => devices, requestDevice: async () => devices, onconnect: null, ondisconnect: null } };
  return new Function('window', 'navigator', 'document', sdkSource + ';return SDK.exports;')({}, nav, doc);
}

function summarize(dev, firmware, headerLen, startChunks, dataChunk) {
  const fr = dev.frames.map((x) => x.info);
  const probe = fr.filter((x) => x.msgId === MSG.R_SOC_VERSION || x.msgId === MSG.R_ACTIVATION_TIME || x.msgId === MSG.R_DP_VERSION).length;
  const dp = dev.frames.filter((x) => x.info.msgId >= 110 && x.info.msgId <= 113);
  const seq = dp.map((x) => x.info.name);
  const compact = [];
  for (const n of seq) { const last = compact[compact.length - 1]; if (last && last[0] === n) last[1]++; else compact.push([n, 1]); }
  const startBytes = Buffer.concat(dp.filter((x) => x.info.msgId === MSG.START).map((x) => Buffer.from(x.payload)));
  const txBytes = Buffer.concat(dp.filter((x) => x.info.msgId === MSG.TRANSMIT).map((x) => Buffer.from(x.payload)));
  const fw = Buffer.from(firmware);
  const sizes = {};
  for (const x of dp) sizes[x.info.name] = [...new Set([...(sizes[x.info.name] || []), x.info.payloadLen])].sort((p, q) => p - q);
  return {
    probeFrames: probe,
    dpFrames: dp.length,
    sequence: compact.map(([n, c]) => `${n}x${c}`).join(' > '),
    reportBytes: [...new Set(fr.map((x) => x.reportBytes))],
    allCrcOk: fr.every((x) => x.crcOk),
    allMagicFD: fr.every((x) => x.magic === 0xfd),
    payloadSizesByMessage: sizes,
    startBytesEqualFirst64: startBytes.length === 64 && startBytes.equals(fw.subarray(0, 64)),
    transmitReassemblesRest: txBytes.equals(fw.subarray(64)),
    imageBytes: fw.length,
    framesAfterFailure: dev.afterFailure,
  };
}

async function timed(fn, limitMs) {
  const t0 = Date.now();
  const r = await Promise.race([fn(), new Promise((res) => setTimeout(() => res('HARNESS-TIMEOUT'), limitMs))]);
  return { result: r, ms: Date.now() - t0 };
}

(async () => {
  const ultraFw = fs.readFileSync(ultraFwPath);
  const air2Fw = fs.readFileSync(air2FwPath);
  const out = { notes: 'offline simulation of XREAL web-updater code against a fake HID device; no hardware involved', scenarios: {} };
  const ultraVersions = { dp: '1140', soc: '12.1.00.502_20250408' };
  const air2Versions = { dp: '1140', soc: '09.1.00.180_20240507' };

  async function ultra(name, rule, opts = {}) {
    const progress = [];
    const dev = new FakeHid({ productId: opts.productId || 1062, reportLen: opts.reportLen || 512, rule, versions: ultraVersions });
    const sdk = loadSdk([dev], progress);
    const { result, ms } = await timed(() => sdk.ultraSDK.upgradeInDp(ab(ultraFw)), opts.limit || 30000);
    const s = summarize(dev, ultraFw);
    out.scenarios[name] = { returned: result, ms, progressSamples: progress.slice(0, 3).concat(progress.slice(-2)), ...s };
    if (process.env.DUMP_DIR && /happy/.test(name)) fs.writeFileSync(process.env.DUMP_DIR + '/ultra_frames.bin', Buffer.concat(dev.frames.filter((x) => x.info.msgId >= 110).map((x) => Buffer.from(x.raw))));
  }

  await ultra('ULTRA happy path (PID 0x0426, 512-byte reports)', () => undefined);
  await ultra('ULTRA TRANSMIT #50 returns status 5', (i) => (i.msgId === MSG.TRANSMIT && i.n === 50 ? { status: 5 } : undefined));
  await ultra('ULTRA TRANSMIT #50 gets no answer (timeout)', (i) => (i.msgId === MSG.TRANSMIT && i.n === 50 ? { drop: true } : undefined), { limit: 20000 });
  await ultra('ULTRA FINISH returns status 5', (i) => (i.msgId === MSG.FINISH ? { status: 5 } : undefined));
  await ultra('ULTRA PREPARE returns status 5', (i) => (i.msgId === MSG.PREPARE ? { status: 5 } : undefined));
  await ultra('ULTRA START returns status 5', (i) => (i.msgId === MSG.START ? { status: 5 } : undefined));
  await ultra('ULTRA USB error at TRANSMIT #30 (cable pulled)', (i) => (i.msgId === MSG.TRANSMIT && i.n === 30 ? { fail: true } : undefined));
  await ultra('ULTRA in bootloader mode (PID 0x0425, 1024-byte reports)', () => undefined, { productId: 1061, reportLen: 1024 });

  async function air2(name, rule, productId) {
    const progress = [];
    const dev = new FakeHid({ productId, reportLen: 64, rule, versions: air2Versions });
    const sdk = loadSdk([dev], progress);
    const { result, ms } = await timed(() => sdk.air2SDK.upgradeInDp(ab(air2Fw)), 30000);
    const s = summarize(dev, air2Fw);
    out.scenarios[name] = { returned: result, ms, ...s };
    if (process.env.DUMP_DIR && /happy/.test(name)) fs.writeFileSync(process.env.DUMP_DIR + '/air2_frames.bin', Buffer.concat(dev.frames.filter((x) => x.info.msgId >= 110).map((x) => Buffer.from(x.raw))));
  }
  await air2('AIR2PRO happy path (PID 0x0432, 64-byte reports)', () => undefined, 1074);
  await air2('AIR2PRO FINISH returns status 5', (i) => (i.msgId === MSG.FINISH ? { status: 5 } : undefined), 1074);

  const text = JSON.stringify(out, null, 2);
  if (outPath) fs.writeFileSync(outPath, text);
  for (const [k, v] of Object.entries(out.scenarios)) {
    console.log('\n## ' + k);
    console.log(`  returned=${v.returned}  (${v.ms} ms)  frames: probe=${v.probeFrames} dp=${v.dpFrames}  report bytes=${JSON.stringify(v.reportBytes)}`);
    console.log(`  sequence: ${v.sequence || '(no DP frames)'}`);
    console.log(`  payload sizes: ${JSON.stringify(v.payloadSizesByMessage)}  CRC ok=${v.allCrcOk}  magic FD=${v.allMagicFD}`);
    console.log(`  START==image[0:64]: ${v.startBytesEqualFirst64}  TRANSMIT==image[64:]: ${v.transmitReassemblesRest}  frames sent after first failure: ${v.framesAfterFailure}`);
  }
  process.exit(0);
})().catch((e) => { console.error('HARNESS ERROR', e); process.exit(1); });

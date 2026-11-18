// Ultra XR: the real room for WebXR pages.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The glasses' tracking cameras find the floor, table tops and walls; the Ultra XR app serves them on this phone at
// http://localhost:8765/room.json. This script reads that a few times a second, lines it up with the page's own
// reference space (the head is in both, so no setting is needed), and offers
//
//   const room = await UltraXRRoom.attach(session)   // room.ref is a 'local-floor' reference space
//   room.update(frame)                                // once a frame, before using it
//   room.tracked                                      // whether the cameras know where the head is right now
//   room.floorY, room.measured, room.surfaces         // in room.ref: y of the floor, whether cameras measured it,
//                                                     // and rectangles { kind, c, u, v, n, hu, hv, corners }
//   room.raycast(origin, direction)                   // -> { point, normal, distance, kind, surface } or null
//
// and, for pages written for the standard API, it answers 'hit-test', 'plane-detection' and 'anchors' itself, because
// the browser on the glasses has none of them: requestHitTestSource, frame.getHitTestResults, frame.detectedPlanes,
// hitResult.createAnchor and frame.createAnchor work as the WebXR modules describe them.
(function () {
  'use strict';
  if (window.UltraXRRoom) return;
  const BASE = (function () { try { return new URL(document.currentScript.src).origin; } catch (e) { return 'http://localhost:8765'; } })();

  // ---- small vector and quaternion helpers on plain arrays
  const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
  const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
  const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
  const qmul = (a, b) => [a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1], a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0], a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3], a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]];
  const qrot = (q, v) => { const t = mul(cross(q, v), 2); return add(add(v, mul(t, q[3])), cross(q, t)); };
  const yawOf = (q) => { const f = qrot(q, [0, 0, -1]); return Math.atan2(-f[0], -f[2]); };
  const rotY = (a, v) => { const c = Math.cos(a), s = Math.sin(a); return [c * v[0] + s * v[2], v[1], -s * v[0] + c * v[2]]; };
  // the orientation whose +y is n (a surface's normal), with z as close to "toward" as it can be
  function qFromY(n, toward) {
    const y = norm(n); let z = sub(toward, mul(y, dot(toward, y)));
    if (Math.hypot(z[0], z[1], z[2]) < 1e-4) { z = Math.abs(y[1]) < 0.9 ? cross([0, 1, 0], y) : [0, 0, 1]; }
    z = norm(z); const x = cross(y, z);
    const m00 = x[0], m01 = y[0], m02 = z[0], m10 = x[1], m11 = y[1], m12 = z[1], m20 = x[2], m21 = y[2], m22 = z[2];
    const tr = m00 + m11 + m22; let q;
    if (tr > 0) { const s = Math.sqrt(tr + 1) * 2; q = [(m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s]; }
    else if (m00 > m11 && m00 > m22) { const s = Math.sqrt(1 + m00 - m11 - m22) * 2; q = [0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s]; }
    else if (m11 > m22) { const s = Math.sqrt(1 + m11 - m00 - m22) * 2; q = [(m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s]; }
    else { const s = Math.sqrt(1 + m22 - m00 - m11) * 2; q = [(m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s]; }
    const l = Math.hypot(q[0], q[1], q[2], q[3]); return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
  }
  const P = (p) => [p.x, p.y, p.z], Qa = (q) => [q.x, q.y, q.z, q.w];

  class Room {
    constructor(session) {
      this.session = session; this.ref = null;
      this.ready = false;          // lined up with the page's space at least once
      this.measured = false;       // the cameras have found the floor (otherwise it is the height setting)
      this.floorY = 0; this.surfaces = []; this.points = []; this.tracked = false;
      this.yaw = 0; this.shift = [0, 0, 0];
      this._data = null; this._fresh = null; this._lastHead = null; this._ended = false; this.packets = 0; this.error = null;
      session.addEventListener('end', () => { this._ended = true; });
      this._poll();
    }
    async _poll() {
      while (!this._ended) {
        try {
          const r = await fetch(BASE + '/room.json', { cache: 'no-store' });
          const d = await r.json();
          if (d && d.session) { d._at = performance.now(); this._data = d; this._fresh = d; this.packets++; this.error = null; }
        } catch (e) { this.error = String(e); }
        await new Promise((ok) => setTimeout(ok, 150));
      }
    }
    // room.json's frame -> this.ref
    _toRef(p) { return add(rotY(this.yaw, p), this.shift); }
    _dirToRef(v) { return rotY(this.yaw, v); }
    /** Once a frame. Lines the room up with the page (from the head, which both know) and refreshes the surfaces. */
    update(frame) {
      if (!this.ref) return;
      const d = this._fresh;
      if (d && d.head && performance.now() - d._at < 60 && d.head.age_ms < 100) {
        this._fresh = null;
        const pose = frame.getViewerPose(this.ref);
        if (pose) {
          // The page's space and the app's differ by one fixed turn and shift (until a recentre). A reading taken while
          // the head moves is off, because the app's pose and the page's are from slightly different moments: a head
          // turning at 100 degrees a second is 5 to 10 degrees further on. So only readings from a calm head count
          // (under 1.5 cm and 0.6 degrees since the reading before), and the answer is the middle of the last 21 of
          // them, held still: one odd reading moves nothing.
          const hp = d.head.p, hy = yawOf(d.head.q), lh = this._lastHead;
          let dyaw = lh ? hy - lh.yaw : 0; while (dyaw > Math.PI) dyaw -= 2 * Math.PI; while (dyaw < -Math.PI) dyaw += 2 * Math.PI;
          const calm = !!lh && Math.hypot(hp[0] - lh.p[0], hp[1] - lh.p[1], hp[2] - lh.p[2]) < 0.015 && Math.abs(dyaw) < 0.0105;
          this._lastHead = { p: hp, yaw: hy };
          const yaw = yawOf(Qa(pose.transform.orientation)) - hy;
          const shift = sub(P(pose.transform.position), rotY(yaw, hp));
          if (!this.ready) { this.yaw = yaw; this.shift = shift; this.ready = true; this._fits = []; this._odd = 0; }
          else if (calm) {
            let dy = yaw - this.yaw; while (dy > Math.PI) dy -= 2 * Math.PI; while (dy < -Math.PI) dy += 2 * Math.PI;
            const far = Math.abs(dy) > 0.12 || Math.hypot(shift[0] - this.shift[0], shift[1] - this.shift[1], shift[2] - this.shift[2]) > 0.25;
            // far off three calm readings running: the world was recentred; start again from here
            if (far) { if (++this._odd >= 3) { this._fits = []; this._odd = 0; this.yaw = yaw; this.shift = shift; } }
            else {
              this._odd = 0; this._fits.push([this.yaw + dy, shift[0], shift[1], shift[2]]); if (this._fits.length > 21) this._fits.shift();
              const mid = (k) => { const v = this._fits.map((f) => f[k]).sort((a, b) => a - b); return v[v.length >> 1]; };
              const ny = mid(0), ns = [mid(1), mid(2), mid(3)];
              if (Math.abs(ny - this.yaw) > 0.002 || Math.hypot(ns[0] - this.shift[0], ns[1] - this.shift[1], ns[2] - this.shift[2]) > 0.004) { this.yaw = ny; this.shift = ns; }
            }
          }
        }
      }
      const data = this._data;
      if (!data || !this.ready || data === this._shown && this._shownYaw === this.yaw && this._shownShift === this.shift) return;
      this._shown = data; this._shownYaw = this.yaw; this._shownShift = this.shift;
      this.measured = !!(data.floor && data.floor.measured);
      this.tracked = !!data.tracked;       // the cameras know where the head is (otherwise the glasses only know which way it faces)
      this.floorY = this._toRef([0, data.floor ? data.floor.y : 0, 0])[1];
      // where the cameras measured things in their newest picture (room.points: [x, y, z] each, in room.ref)
      this.points = []; const dp = data.points || [];
      for (let i = 0; i + 2 < dp.length; i += 3) this.points.push(this._toRef([dp[i], dp[i + 1], dp[i + 2]]));
      this.surfaces = (data.surfaces || []).map((s, i) => {
        const c = this._toRef(s.c), u = this._dirToRef(s.u), v = this._dirToRef(s.v), n = this._dirToRef(s.n);
        const corners = [[-1, -1], [1, -1], [1, 1], [-1, 1]].map(([a, b]) => add(add(c, mul(u, a * s.hu)), mul(v, b * s.hv)));
        return { kind: s.kind, c, u, v, n, hu: s.hu, hv: s.hv, corners, support: s.support, index: i };
      });
      // no floor rectangle from the cameras yet: the floor still exists, at the height the runtime uses
      if (!this.surfaces.some((s) => s.kind === 'floor')) this.surfaces.push({ kind: 'floor', c: [0, this.floorY, 0], u: [1, 0, 0], v: [0, 0, -1], n: [0, 1, 0], hu: 3, hv: 3, corners: [[-3, this.floorY, 3], [3, this.floorY, 3], [3, this.floorY, -3], [-3, this.floorY, -3]], support: 0, index: -1 });
    }
    /**
     * Where a ray (origin and direction as [x, y, z] in room.ref) first meets the room. The floor goes on for ever;
     * table tops and walls are met only where the cameras saw them (plus "grow" metres around, 0.05 by default).
     */
    raycast(origin, direction, opts) {
      const grow = opts && opts.grow !== undefined ? opts.grow : 0.05, kinds = opts && opts.kinds;
      const dir = norm(direction); let best = null;
      for (const s of this.surfaces) {
        if (kinds && !kinds.includes(s.kind)) continue;
        const den = dot(dir, s.n); if (Math.abs(den) < 1e-6) continue;
        const t = dot(sub(s.c, origin), s.n) / den; if (t <= 0.02) continue;
        const p = add(origin, mul(dir, t));
        if (s.kind !== 'floor') { const r = sub(p, s.c); if (Math.abs(dot(r, s.u)) > s.hu + grow || Math.abs(dot(r, s.v)) > s.hv + grow) continue; }
        if (!best || t < best.distance) best = { point: p, normal: den < 0 ? s.n : mul(s.n, -1), distance: t, kind: s.kind, surface: s };
      }
      return best;
    }
  }

  async function attach(session) {
    if (session.__uxrRoom) return session.__uxrRoom;
    const room = new Room(session); session.__uxrRoom = room;
    try { room.ref = await session.requestReferenceSpace('local-floor'); } catch (e) { room.ref = await session.requestReferenceSpace('local'); }
    return room;
  }
  window.UltraXRRoom = { attach, version: 1, base: BASE };

  // ------------------------------------------------------------ the standard modules, answered here
  if (!navigator.xr || !window.XRFrame || !window.XRSession) return;
  const OURS = ['hit-test', 'plane-detection', 'anchors'];
  const origRequest = navigator.xr.requestSession.bind(navigator.xr);
  const origGetPose = XRFrame.prototype.getPose;
  let active = null;      // { room, wanted, anchors:Set, planes:Map }

  function rigid(p, q) { return new XRRigidTransform({ x: p[0], y: p[1], z: p[2], w: 1 }, { x: q[0], y: q[1], z: q[2], w: q[3] }); }
  // a pose given in room.ref, as an XRPose in "base"
  function poseIn(frame, p, q, base) {
    const room = active && active.room; if (!room) return null;
    if (base === room.ref) return { transform: rigid(p, q), emulatedPosition: false, linearVelocity: null, angularVelocity: null };
    const t = origGetPose.call(frame, room.ref, base); if (!t) return null;
    const tq = Qa(t.transform.orientation), tp = P(t.transform.position);
    return { transform: rigid(add(qrot(tq, p), tp), qmul(tq, q)), emulatedPosition: false, linearVelocity: null, angularVelocity: null };
  }
  class FakeSpace { constructor(pose) { this.__uxr = true; this._pose = pose; } }      // pose(): { p, q } in room.ref

  class HitResult {
    constructor(frame, hit, toward) { this._frame = frame; this._p = hit.point; this._q = qFromY(hit.normal, toward); this._hit = hit; }
    getPose(base) { return poseIn(this._frame, this._p, this._q, base); }
    createAnchor() { return Promise.resolve(makeAnchor(this._p, this._q)); }
  }
  function makeAnchor(p, q) {
    // An anchor is a place in the room. It is kept in room.json's frame, so it stays put when the page's space is lined up afresh.
    const room = active.room, inv = -room.yaw;
    const world = rotY(inv, sub(p, room.shift)), wq = qmul([0, Math.sin(inv / 2), 0, Math.cos(inv / 2)], q);
    const a = { anchorSpace: new FakeSpace(() => ({ p: room._toRef(world), q: qmul([0, Math.sin(room.yaw / 2), 0, Math.cos(room.yaw / 2)], wq) })), delete() { active && active.anchors.delete(a); } };
    active.anchors.add(a); return a;
  }
  function castFrom(frame, space, offsetRay) {
    const room = active.room; if (!room.ready) return null;
    const pose = origGetPose.call(frame, space, room.ref); if (!pose) return null;
    const q = Qa(pose.transform.orientation), p = P(pose.transform.position);
    const o = offsetRay ? add(p, qrot(q, P(offsetRay.origin))) : p, d = qrot(q, offsetRay ? P(offsetRay.direction) : [0, 0, -1]);
    const hit = room.raycast(o, d); if (!hit) return null;
    return new HitResult(frame, hit, mul(d, -1));
  }

  XRFrame.prototype.getPose = function (space, base) {
    if (space && space.__uxr) { const s = space._pose(); return poseIn(this, s.p, s.q, base); }
    if (base && base.__uxr) return null;
    return origGetPose.call(this, space, base);
  };
  XRFrame.prototype.getHitTestResults = function (source) {
    if (!active || !source || source._cancelled) return [];
    const r = castFrom(this, source.space, source.offsetRay); return r ? [r] : [];
  };
  XRFrame.prototype.getHitTestResultsForTransientInput = function (source) {
    if (!active || !source || source._cancelled) return [];
    const out = [];
    for (const input of this.session.inputSources) {
      if (source.profile && !(input.profiles || []).includes(source.profile)) continue;
      const r = castFrom(this, input.targetRaySpace, source.offsetRay); if (r) out.push({ inputSource: input, results: [r] });
    }
    return out;
  };
  XRFrame.prototype.createAnchor = function (pose, space) {
    if (!active) return Promise.reject(new DOMException('no session', 'InvalidStateError'));
    const t = origGetPose.call(this, space, active.room.ref); if (!t) return Promise.reject(new DOMException('space not locatable', 'InvalidStateError'));
    const tq = Qa(t.transform.orientation), tp = P(t.transform.position);
    return Promise.resolve(makeAnchor(add(qrot(tq, P(pose.position)), tp), qmul(tq, Qa(pose.orientation))));
  };
  Object.defineProperty(XRFrame.prototype, 'trackedAnchors', { configurable: true, get() { return active ? active.anchors : new Set(); } });
  Object.defineProperty(XRFrame.prototype, 'detectedPlanes', { configurable: true, get() {
    if (!active) return new Set();
    const room = active.room, keep = new Set(), now = performance.now();
    for (const s of room.surfaces) {
      if (s.index < 0) continue;
      const key = s.kind + s.index; let pl = active.planes.get(key);
      if (!pl) { pl = { orientation: s.kind === 'wall' ? 'vertical' : 'horizontal', semanticLabel: s.kind, lastChangedTime: now }; active.planes.set(key, pl); }
      // plane space: origin at the centre, +y the normal, x along the long side; the outline in its x-z plane, closed
      const q = qFromY(s.n, cross(s.u, s.n));
      if (!pl._c || Math.hypot(pl._c[0] - s.c[0], pl._c[1] - s.c[1], pl._c[2] - s.c[2]) > 0.02 || pl._hu !== s.hu || pl._hv !== s.hv) pl.lastChangedTime = now;
      pl._c = s.c; pl._hu = s.hu; pl._hv = s.hv;
      pl.planeSpace = new FakeSpace(() => ({ p: s.c, q }));
      const ax = qrot(q, [1, 0, 0]), az = qrot(q, [0, 0, 1]);
      const pts = s.corners.map((c) => { const r = sub(c, s.c); return { x: dot(r, ax), y: 0, z: dot(r, az), w: 1 }; });
      pts.push(pts[0]); pl.polygon = pts;
      keep.add(pl);
    }
    return keep;
  } });

  navigator.xr.requestSession = async function (mode, init) {
    init = init || {};
    const req = init.requiredFeatures || [], opt = init.optionalFeatures || [];
    const wanted = new Set([...req, ...opt].filter((f) => OURS.includes(f)));
    const clean = Object.assign({}, init, { requiredFeatures: req.filter((f) => !OURS.includes(f)), optionalFeatures: [...opt.filter((f) => !OURS.includes(f)), 'local-floor'] });
    const session = await origRequest(mode, clean);
    if (mode === 'inline') return session;
    const room = await attach(session);
    const state = { room, wanted, anchors: new Set(), planes: new Map() };
    active = state;
    session.addEventListener('end', () => { if (active === state) active = null; });
    session.requestHitTestSource = (o) => Promise.resolve({ space: o.space, offsetRay: o.offsetRay || null, cancel() { this._cancelled = true; } });
    session.requestHitTestSourceForTransientInput = (o) => Promise.resolve({ profile: o.profile, offsetRay: o.offsetRay || null, cancel() { this._cancelled = true; } });
    const raf = session.requestAnimationFrame.bind(session);
    session.requestAnimationFrame = (cb) => raf((t, frame) => { try { room.update(frame); } catch (e) { room.error = String(e); } cb(t, frame); });
    try {
      const real = session.enabledFeatures ? Array.from(session.enabledFeatures) : [];
      Object.defineProperty(session, 'enabledFeatures', { configurable: true, get() { return [...real, ...wanted]; } });
    } catch (e) {}
    return session;
  };
})();

// =============================================================================
// textures.js - every texture is drawn in code on a <canvas>, so the sim works
// offline and has no image files to download or keep in sync.
// =============================================================================
import * as THREE from 'three';

/** Tiny seeded random so textures look the same every time. */
function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
    return ((s >>> 0) % 100000) / 100000;
  };
}

function canvas(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return [c, c.getContext('2d')];
}

function tex(c, { srgb = true, repeat = null, aniso = 8 } = {}) {
  const t = new THREE.CanvasTexture(c);
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  if (repeat) {
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(repeat[0], repeat[1]);
  }
  t.anisotropy = aniso;
  return t;
}

// -----------------------------------------------------------------------------
// FIELD TILES (§9.2): gray EVA foam, 24 in square, interlocking "puzzle" edges.
// One canvas holds 2 x 2 tiles (48 in) with slightly different shades, and is
// repeated 3 x 3 across the 144 in field. The same drawing, as a height map,
// makes the seams into grooves (bump map).
// -----------------------------------------------------------------------------
export function tileTextures(aniso) {
  const N = 1024;              // pixels for 48 in -> ~21 px per inch
  const perIn = N / 48;
  const [c, g] = canvas(N, N);
  const [b, bg] = canvas(N, N);
  const r = rng(7);
  const shades = [[150, 153, 158], [146, 149, 154], [152, 155, 159], [144, 147, 152]];
  for (let ty = 0; ty < 2; ty++) {
    for (let tx = 0; tx < 2; tx++) {
      const [cr, cg, cb] = shades[ty * 2 + tx];
      g.fillStyle = `rgb(${cr},${cg},${cb})`;
      g.fillRect(tx * N / 2, ty * N / 2, N / 2, N / 2);
    }
  }
  bg.fillStyle = '#808080';
  bg.fillRect(0, 0, N, N);
  // Foam grain: thousands of tiny light/dark specks.
  for (let i = 0; i < 60000; i++) {
    const x = r() * N;
    const y = r() * N;
    const v = r();
    g.fillStyle = v < 0.5 ? `rgba(0,0,0,${0.05 + v * 0.06})` : `rgba(255,255,255,${0.02 + (v - 0.5) * 0.05})`;
    g.fillRect(x, y, 1.2, 1.2);
    bg.fillStyle = v < 0.5 ? 'rgba(60,60,60,0.35)' : 'rgba(170,170,170,0.35)';
    bg.fillRect(x, y, 1.5, 1.5);
  }
  // Puzzle seams on every tile edge. Tabs are ~1.6 in wide, ~0.6 in deep. The
  // path is drawn at both ends of the canvas so the pattern repeats seamlessly.
  const tabW = 1.6 * perIn;
  const tabD = 0.6 * perIn;
  const seamPath = (ctx, along, at, vertical) => {
    ctx.beginPath();
    let s = 0;
    let out = 1;
    const pt = (u, v) => (vertical ? ctx.lineTo(at + v, u) : ctx.lineTo(u, at + v));
    if (vertical) ctx.moveTo(at, 0); else ctx.moveTo(0, at);
    while (s < along) {
      const d = out * tabD / 2;
      pt(s, d);
      pt(s + tabW, d);
      s += tabW;
      out = -out;
    }
    ctx.stroke();
  };
  for (const [ctx, color, width] of [[g, 'rgba(40,42,46,0.85)', 1.6], [bg, '#000000', 3.5]]) {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineJoin = 'round';
    for (const at of [0, N / 2, N]) {
      seamPath(ctx, N, at, true);
      seamPath(ctx, N, at, false);
    }
  }
  const map = tex(c, { repeat: [3, 3], aniso });
  const bump = tex(b, { srgb: false, repeat: [3, 3], aniso });
  return { map, bump };
}

// -----------------------------------------------------------------------------
// Brushed aluminum: fine streaks along U. Used as a roughness map.
// -----------------------------------------------------------------------------
export function brushedTexture(aniso) {
  const [c, g] = canvas(256, 256);
  const r = rng(11);
  g.fillStyle = '#6a6a6a';
  g.fillRect(0, 0, 256, 256);
  for (let i = 0; i < 900; i++) {
    const y = r() * 256;
    const v = 70 + r() * 90;
    g.strokeStyle = `rgba(${v},${v},${v},0.35)`;
    g.lineWidth = 0.6 + r();
    g.beginPath();
    g.moveTo(0, y);
    g.lineTo(256, y + (r() - 0.5) * 2);
    g.stroke();
  }
  return tex(c, { srgb: false, repeat: [4, 1], aniso });
}

// -----------------------------------------------------------------------------
// goBILDA U-channel face: aluminum with the classic hole pattern
// (a big 14 mm hole every 24 mm, four 4 mm holes around it). Used as an alpha
// map so the holes are really see-through. One tile = 24 mm.
// -----------------------------------------------------------------------------
export function channelHoleTexture() {
  const [c, g] = canvas(128, 128);
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, 128, 128);
  g.fillStyle = '#000000';
  const px = 128 / 24; // px per mm
  const hole = (x, y, d) => { g.beginPath(); g.arc(x * px, y * px, d / 2 * px, 0, Math.PI * 2); g.fill(); };
  hole(12, 12, 14);
  for (const [dx, dy] of [[-8, -8], [8, -8], [-8, 8], [8, 8]]) hole(12 + dx, 12 + dy, 4);
  const t = tex(c, { srgb: false });
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  return t;
}

// -----------------------------------------------------------------------------
// Pocketed aluminum side plate (shooter): a solid border and top (where the
// flywheel axle and motors bolt on) with triangular lightening pockets below.
// Used as an alpha map on the plate's faces (u = along the plate, v = up).
// -----------------------------------------------------------------------------
export function pocketTexture() {
  const W = 256;
  const H = 512;
  const [c, g] = canvas(W, H);
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, W, H);
  g.fillStyle = '#000000';
  g.lineJoin = 'round';
  const border = 22;
  const web = 16;
  const top = H * 0.38;                 // solid region at the top (canvas y = 0 is the top)
  const rows = 3;
  const rowH = (H - border - top) / rows;
  const cols = 2;
  const colW = (W - 2 * border) / cols;
  const tri = pts => {
    g.beginPath();
    g.moveTo(...pts[0]);
    for (const p of pts.slice(1)) g.lineTo(...p);
    g.closePath();
    g.fill();
  };
  for (let r = 0; r < rows; r++) {
    for (let k = 0; k < cols; k++) {
      const x0 = border + k * colW + web / 2;
      const x1 = x0 + colW - web;
      const y0 = top + r * rowH + web / 2;
      const y1 = y0 + rowH - web;
      // Two triangles per cell, split by a diagonal web (a truss).
      const d = web * 0.75;
      tri([[x0, y0 + d], [x0, y1], [x1 - d, y1]]);
      tri([[x0 + d, y0], [x1, y0], [x1, y1 - d]]);
    }
  }
  // Bolt holes along the top edge and around the axle.
  const hole = (x, y, rad) => { g.beginPath(); g.arc(x, y, rad, 0, Math.PI * 2); g.fill(); };
  hole(W * 0.62, H * 0.16, 14);
  for (const x of [W * 0.15, W * 0.35, W * 0.85]) hole(x, H * 0.06, 5);
  const t = tex(c, { srgb: false });
  return t;
}

// -----------------------------------------------------------------------------
// Wiffle-style balls: small round holes all over. White with dark holes; the
// material's color tints it yellow / red / blue.
// -----------------------------------------------------------------------------
export function ballTextures(aniso) {
  const [c, g] = canvas(256, 128);
  const [b, bg] = canvas(256, 128);
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, 256, 128);
  bg.fillStyle = '#ffffff';
  bg.fillRect(0, 0, 256, 128);
  // Rows of holes; fewer near the poles (equirectangular squeeze).
  for (let row = 1; row < 6; row++) {
    const lat = (row / 6 - 0.5) * Math.PI;
    const n = Math.max(2, Math.round(8 * Math.cos(lat)));
    for (let i = 0; i < n; i++) {
      const x = ((i + (row % 2) * 0.5) / n) * 256;
      const y = (row / 6) * 128;
      const rx = 7 / Math.max(0.35, Math.cos(lat));
      for (const [ctx, col] of [[g, 'rgba(25,20,10,0.75)'], [bg, '#000000']]) {
        ctx.fillStyle = col;
        ctx.beginPath();
        ctx.ellipse(x, y, rx, 7, 0, 0, Math.PI * 2);
        ctx.ellipse(x + 256, y, rx, 7, 0, 0, Math.PI * 2);
        ctx.ellipse(x - 256, y, rx, 7, 0, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }
  return { map: tex(c, { aniso }), bump: tex(b, { srgb: false, aniso }) };
}

// -----------------------------------------------------------------------------
// An AprilTag-looking square (36h11 style: black border, 6x6 data cells).
// NOTE: the bit pattern is a deterministic stand-in, not the real 36h11 code
// for that ID - it's for looks only (the simulated Limelight doesn't read pixels).
// -----------------------------------------------------------------------------
export function aprilTagTexture(id) {
  const [c, g] = canvas(80, 80);
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, 80, 80);
  g.fillStyle = '#000000';
  g.fillRect(8, 8, 64, 64);
  const r = rng(id * 7919 + 13);
  g.fillStyle = '#ffffff';
  for (let y = 0; y < 6; y++) {
    for (let x = 0; x < 6; x++) {
      if (r() > 0.5) g.fillRect(16 + x * 8, 16 + y * 8, 8, 8);
    }
  }
  const t = tex(c);
  t.magFilter = THREE.NearestFilter;
  return t;
}

/** A robot sign / banner: colored background with bold white text. */
export function signTexture(text, bg, fg = '#ffffff', w = 512, h = 128) {
  const [c, g] = canvas(w, h);
  g.fillStyle = bg;
  g.fillRect(0, 0, w, h);
  g.fillStyle = fg;
  let size = h * 0.72;
  g.font = `900 ${size}px -apple-system, "Helvetica Neue", Arial, sans-serif`;
  while (g.measureText(text).width > w * 0.9 && size > 10) {
    size -= 4;
    g.font = `900 ${size}px -apple-system, "Helvetica Neue", Arial, sans-serif`;
  }
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillText(text, w / 2, h / 2 + h * 0.04);
  return tex(c);
}

/** The yellow BIOBUZZ banner on the HIVE crossbar (Fig 9-8), with a honeycomb edge. */
export function bannerTexture() {
  const [c, g] = canvas(1024, 160);
  g.fillStyle = '#f4d23a';
  g.fillRect(0, 0, 1024, 160);
  // honeycomb strip on the left
  g.strokeStyle = 'rgba(40,30,0,0.55)';
  g.lineWidth = 3;
  for (let row = 0; row < 5; row++) {
    for (let col = 0; col < 4; col++) {
      const x = 20 + col * 34 + (row % 2) * 17;
      const y = 14 + row * 30;
      g.beginPath();
      for (let k = 0; k < 6; k++) {
        const a = Math.PI / 3 * k + Math.PI / 6;
        g.lineTo(x + Math.cos(a) * 16, y + Math.sin(a) * 16);
      }
      g.closePath();
      g.stroke();
    }
  }
  g.fillStyle = '#111111';
  g.font = '900 104px "Arial Black", Arial, sans-serif';
  g.textBaseline = 'middle';
  g.fillText('BIOBUZZ', 330, 86);
  g.font = 'bold 26px Arial, sans-serif';
  g.fillText('FIRST TECH CHALLENGE', 180, 30);
  return tex(c);
}

/**
 * Motion-blurred flywheel: at thousands of RPM your eye sees a smeared disc,
 * not spokes. Concentric rings with soft radial streaks, mostly transparent.
 */
export function blurDiscTexture() {
  const [c, g] = canvas(256, 256);
  const cx = 128;
  const grd = g.createRadialGradient(cx, cx, 10, cx, cx, 126);
  grd.addColorStop(0, 'rgba(60,60,60,0.95)');
  grd.addColorStop(0.25, 'rgba(90,90,95,0.55)');
  grd.addColorStop(0.8, 'rgba(70,70,75,0.45)');
  grd.addColorStop(0.93, 'rgba(25,25,25,0.95)');
  grd.addColorStop(1, 'rgba(25,25,25,0)');
  g.fillStyle = grd;
  g.beginPath();
  g.arc(cx, cx, 126, 0, Math.PI * 2);
  g.fill();
  const r = rng(3);
  for (let i = 0; i < 90; i++) {
    const a0 = r() * Math.PI * 2;
    const rad = 20 + r() * 100;
    g.strokeStyle = `rgba(210,210,215,${0.05 + r() * 0.12})`;
    g.lineWidth = 1 + r() * 2;
    g.beginPath();
    g.arc(cx, cx, rad, a0, a0 + 0.6 + r() * 1.2);
    g.stroke();
  }
  return tex(c);
}

/** Spoked wheel face (for when the flywheel is slow enough to see). */
export function spokeTexture() {
  const [c, g] = canvas(256, 256);
  g.clearRect(0, 0, 256, 256);
  g.fillStyle = '#2a2a2c';
  g.beginPath();
  g.arc(128, 128, 126, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = '#9ba1a8';
  g.beginPath();
  g.arc(128, 128, 108, 0, Math.PI * 2);
  g.fill();
  g.globalCompositeOperation = 'destination-out';
  for (let k = 0; k < 5; k++) {
    const a = k * Math.PI * 2 / 5;
    g.beginPath();
    g.moveTo(128, 128);
    g.arc(128, 128, 96, a + 0.18, a + Math.PI * 2 / 5 - 0.18);
    g.closePath();
    g.fill();
  }
  g.globalCompositeOperation = 'source-over';
  g.fillStyle = '#c9ced4';
  g.beginPath();
  g.arc(128, 128, 30, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = '#333';
  g.beginPath();
  g.arc(128, 128, 8, 0, Math.PI * 2);
  g.fill();
  return tex(c);
}

/** Ribbed compliant-wheel surface for the intake rollers (see-able rotation). */
export function rollerTexture() {
  const [c, g] = canvas(128, 32);
  g.fillStyle = '#2f8a3a';
  g.fillRect(0, 0, 128, 32);
  for (let i = 0; i < 16; i++) {
    g.fillStyle = i % 2 ? '#256f2e' : '#3aa047';
    g.fillRect(i * 8, 0, 4, 32);
  }
  return tex(c);
}

/** Carpet-ish floor around the field (venue floor). */
export function floorTexture(aniso) {
  const [c, g] = canvas(256, 256);
  g.fillStyle = '#2b2e33';
  g.fillRect(0, 0, 256, 256);
  const r = rng(5);
  for (let i = 0; i < 12000; i++) {
    const v = 30 + r() * 30;
    g.fillStyle = `rgba(${v},${v + 2},${v + 6},0.5)`;
    g.fillRect(r() * 256, r() * 256, 1, 1);
  }
  return tex(c, { repeat: [12, 8], aniso });
}

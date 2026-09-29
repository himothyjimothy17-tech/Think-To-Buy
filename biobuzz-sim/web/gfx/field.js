// =============================================================================
// field.js - the realistic BIOBUZZ field (Quality: Medium / High).
//
// Every size comes from the "field" message (config/game.jsonc = the manual).
// The CELL pentagon and floor offset are the SAME numbers the physics uses
// (Hive.java), so what you see is what scores.
// Units: inches, Z up, +x toward the blue wall, +y away from the audience.
// =============================================================================
import * as THREE from 'three';
import * as T from './textures.js';

const DEG = Math.PI / 180;

export const ALLIANCE = {
  red: 0xc8202a,     // powder-coated CELL frames, tape
  blue: 0x1f5fd6,
};

/** Shared materials for one quality level. Made once per build, disposed with the field. */
export function makeMaterials(q, renderer) {
  const aniso = Math.min(q.aniso, renderer.capabilities.getMaxAnisotropy());
  const Std = q.physical ? THREE.MeshPhysicalMaterial : THREE.MeshStandardMaterial;
  const brushed = T.brushedTexture(aniso);
  const tiles = T.tileTextures(aniso);
  const balls = T.ballTextures(aniso);
  // Brushed aluminum: slightly rough so it reads as silver from every angle
  // instead of mirroring the dark ceiling.
  const alu = new Std({ color: 0xd0d4d9, metalness: 0.85, roughness: 0.42, roughnessMap: brushed });
  const m = {
    aniso,
    tile: new THREE.MeshStandardMaterial({ color: 0xa9adb2, map: tiles.map, bumpMap: tiles.bump, bumpScale: 0.6,
      roughness: 0.93, metalness: 0 }),
    tileSide: new THREE.MeshStandardMaterial({ color: 0x55595f, roughness: 0.95 }),
    floor: new THREE.MeshStandardMaterial({ color: 0xffffff, map: T.floorTexture(aniso), roughness: 1.0 }),
    alu,
    aluDark: new Std({ color: 0x8e949b, metalness: 1.0, roughness: 0.45, roughnessMap: brushed }),
    polycarb: new Std({ color: 0xf4f8fc, roughness: 0.06, metalness: 0, transparent: true, opacity: 0.16,
      depthWrite: false, side: THREE.DoubleSide, ...(q.physical ? { clearcoat: 1, clearcoatRoughness: 0.05 } : {}) }),
    cellPanel: new Std({ color: 0xe9eef5, roughness: 0.12, metalness: 0, transparent: true, opacity: 0.28,
      depthWrite: false, side: THREE.DoubleSide, ...(q.physical ? { clearcoat: 0.8 } : {}) }),
    blackPlastic: new THREE.MeshStandardMaterial({ color: 0x1b1c1f, roughness: 0.55, metalness: 0.05 }),
    frameRed: new Std({ color: ALLIANCE.red, roughness: 0.42, metalness: 0.25, ...(q.physical ? { clearcoat: 0.4 } : {}) }),
    frameBlue: new Std({ color: ALLIANCE.blue, roughness: 0.42, metalness: 0.25, ...(q.physical ? { clearcoat: 0.4 } : {}) }),
    greenPipe: new Std({ color: 0x49a33f, roughness: 0.35, metalness: 0.1, ...(q.physical ? { clearcoat: 0.5 } : {}) }),
    goldPlate: new Std({ color: 0xd49a2e, roughness: 0.4, metalness: 0.2 }),
    purple: new Std({ color: 0x7a3fa0, roughness: 0.45, metalness: 0.2 }),
    banner: new THREE.MeshStandardMaterial({ map: T.bannerTexture(), roughness: 0.6, side: THREE.DoubleSide }),
    ball: [0xf1cc1c, 0xd8262e, 0x2463d6].map(color => new Std({
      color, map: balls.map, bumpMap: balls.bump, bumpScale: 0.25, roughness: 0.55, metalness: 0,
      ...(q.physical ? { clearcoat: 0.25, clearcoatRoughness: 0.4, sheen: 0.3, sheenColor: new THREE.Color(0xffffff) } : {}),
    })),
    tapeRed: new THREE.MeshStandardMaterial({ color: ALLIANCE.red, roughness: 0.35 }),
    tapeBlue: new THREE.MeshStandardMaterial({ color: ALLIANCE.blue, roughness: 0.35 }),
    tagCache: new Map(),
  };
  return m;
}

/** Builds the whole generated field into {@code group}. Returns handles the scene animates. */
export function buildRealField(group, zoneGroup, msg, m, q, parts = {}) {
  const g = msg.game;
  const size = g.field.size;
  const half = size / 2;
  const out = { hiveGroups: {}, flowerRings: [] };
  const want = name => parts[name] !== false;

  // Venue floor around the field.
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(size + 220, size + 140), m.floor);
  floor.position.z = -g.field.tileThickness - 0.05;
  floor.receiveShadow = true;
  group.add(floor);

  if (want('tiles')) {
    // 36 foam tiles as one slab: the texture draws the tiles and their puzzle seams.
    const t = g.field.tileThickness;
    const slab = new THREE.Mesh(new THREE.BoxGeometry(size, size, t),
      [m.tileSide, m.tileSide, m.tileSide, m.tileSide, m.tile, m.tileSide]);
    slab.position.z = -t / 2;
    slab.receiveShadow = true;
    group.add(slab);
  }

  if (want('walls')) buildWalls(group, g, m);
  buildZones(zoneGroup, msg, m, half);

  if (want('flowers')) {
    for (const f of msg.flowers) {
      const fl = buildFlower(f.x, f.y, g.flower, m, half, q);
      out.flowerRings.push(fl.getObjectByName('topRing'));
      group.add(fl);
    }
  }
  if (want('hiveFrame')) group.add(buildHiveFrame(g.hive, m));
  if (want('hives')) {
    for (const side of ['RED', 'BLUE']) {
      const hive = buildHive(g, side, m, q);
      out.hiveGroups[side] = hive;
      group.add(hive);
    }
  }
  group.traverse(o => {
    if (o.isMesh) {
      const transparent = Array.isArray(o.material) ? false : o.material.transparent;
      if (o.castShadow === undefined || o.userData.noShadow) return;
      if (!transparent && o !== floor) o.castShadow = true;
      if (o.receiveShadow !== true) o.receiveShadow = !transparent;
    }
  });
  return out;
}

// -----------------------------------------------------------------------------
// Perimeter: clear polycarbonate panels in aluminum rails, posts every 24 in.
// -----------------------------------------------------------------------------
function buildWalls(group, g, m) {
  const size = g.field.size;
  const half = size / 2;
  const h = g.field.wallHeight;
  for (const [cx, cy, alongX] of [[0, half + 0.25, true], [0, -half - 0.25, true], [half + 0.25, 0, false], [-half - 0.25, 0, false]]) {
    const len = size + 1.5;
    const panel = new THREE.Mesh(alongX ? new THREE.BoxGeometry(len, 0.22, h - 1.6) : new THREE.BoxGeometry(0.22, len, h - 1.6), m.polycarb);
    panel.position.set(cx, cy, 0.4 + (h - 1.6) / 2);
    panel.renderOrder = 2;
    group.add(panel);
    const out = alongX ? Math.sign(cy) : Math.sign(cx);
    for (const [z, th] of [[h - 0.6, 1.2], [0.35, 0.7]]) {
      const rail = new THREE.Mesh(alongX ? new THREE.BoxGeometry(len + 1.5, 1.0, th) : new THREE.BoxGeometry(1.0, len + 1.5, th), m.alu);
      rail.position.set(cx + (alongX ? 0 : out * 0.35), cy + (alongX ? out * 0.35 : 0), z);
      group.add(rail);
    }
    for (let s = -half; s <= half + 0.01; s += 24) {
      const post = new THREE.Mesh(new THREE.BoxGeometry(1.0, 1.0, h), m.aluDark);
      post.position.set(alongX ? s : cx + out * 0.8, alongX ? cy + out * 0.8 : s, h / 2);
      group.add(post);
    }
  }
}

// -----------------------------------------------------------------------------
// Zones (§9.3): alliance-colored tape lines on the tiles, plus a faint tint.
// -----------------------------------------------------------------------------
function buildZones(zoneGroup, msg, m, half) {
  const tapeW = 1.0;
  for (const [alliance, zones] of Object.entries(msg.zones)) {
    const tape = alliance === 'red' ? m.tapeRed : m.tapeBlue;
    const color = alliance === 'red' ? ALLIANCE.red : ALLIANCE.blue;
    for (const [r, tint, z] of [[zones.loadingZone, 0.14, 0.04], [zones.garden, 0.22, 0.04], [zones.allianceArea, 0.10, -0.45]]) {
      const w = r.xMax - r.xMin;
      const h = r.yMax - r.yMin;
      const fill = new THREE.Mesh(new THREE.PlaneGeometry(w, h),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: tint, depthWrite: false }));
      fill.position.set((r.xMin + r.xMax) / 2, (r.yMin + r.yMax) / 2, z);
      fill.userData.noShadow = true;
      zoneGroup.add(fill);
      if (z < 0) continue; // the ALLIANCE AREA is outside the field: no tape
      // Tape just inside the zone edges (not on the walls).
      const edges = [
        [r.xMin + tapeW / 2, (r.yMin + r.yMax) / 2, tapeW, h],
        [r.xMax - tapeW / 2, (r.yMin + r.yMax) / 2, tapeW, h],
        [(r.xMin + r.xMax) / 2, r.yMin + tapeW / 2, w, tapeW],
        [(r.xMin + r.xMax) / 2, r.yMax - tapeW / 2, w, tapeW],
      ];
      for (const [x, y, ew, eh] of edges) {
        if (Math.abs(x) > half - 0.6 || Math.abs(y) > half - 0.6) continue; // skip edges on the wall
        const strip = new THREE.Mesh(new THREE.PlaneGeometry(ew, eh), tape);
        strip.position.set(x, y, 0.03);
        strip.receiveShadow = true;
        zoneGroup.add(strip);
      }
    }
  }
}

// -----------------------------------------------------------------------------
// FLOWER (§9.7, Figure 9-12): black bottom ring on the tiles, a square
// extrusion on the wall side up to the black middle ring, four green HIPS
// pipes to the gold top plate, a purple backstop on the wall side of the top.
// -----------------------------------------------------------------------------
function roundedRectShape(w, h, r, holeR) {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  if (holeR) {
    const hole = new THREE.Path();
    hole.absarc(0, 0, holeR, 0, Math.PI * 2, true);
    s.holes.push(hole);
  }
  return s;
}

function plate(shape, thick, mat, z) {
  const geo = new THREE.ExtrudeGeometry(shape, { depth: thick, bevelEnabled: true, bevelThickness: 0.04, bevelSize: 0.04, bevelSegments: 1, curveSegments: 20 });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.position.z = z;
  return mesh;
}

function buildFlower(x, y, f, m, half, q) {
  const group = new THREE.Group();
  group.position.set(x, y, 0);
  // Local +x points at the wall this FLOWER is mounted on.
  group.rotation.z = Math.abs(x) > Math.abs(y) ? (x > 0 ? 0 : Math.PI) : (y > 0 ? Math.PI / 2 : -Math.PI / 2);
  const topZ = f.topOpeningHeight;
  const ringZ = f.bottomRingHeight;
  const midZ = ringZ + f.retrievalOpeningHeight;
  const holeR = f.topOpeningDiameter / 2;
  // Bottom ring (sits on the tiles; POLLEN rests in its hole).
  group.add(plate(roundedRectShape(4.8, 4.8, 0.9, f.bottomRingHoleDiameter / 2), ringZ, m.blackPlastic, 0));
  // Square extrusion on the wall side, bottom ring -> middle ring.
  const post = new THREE.Mesh(new THREE.BoxGeometry(1.0, 1.0, midZ - ringZ), m.aluDark);
  post.position.set(2.3, 0, ringZ + (midZ - ringZ) / 2);
  group.add(post);
  // Middle ring and top plate.
  group.add(plate(roundedRectShape(5.6, 5.6, 1.1, holeR + 0.05), 0.5, m.blackPlastic, midZ));
  group.add(plate(roundedRectShape(6.2, 6.2, 1.3, holeR), 0.5, m.goldPlate, topZ - 0.5));
  // Black liner around the top opening.
  const liner = new THREE.Mesh(new THREE.TorusGeometry(holeR + 0.12, 0.16, 8, 40), m.blackPlastic);
  liner.position.z = topZ + 0.05;
  group.add(liner);
  // Four green pipes (HIPS), middle ring -> top plate.
  const pipeLen = topZ - 0.5 - (midZ + 0.5);
  for (const [px, py] of [[1, 1], [1, -1], [-1, 1], [-1, -1]]) {
    const pipe = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.45, pipeLen, q.physical ? 20 : 14), m.greenPipe);
    pipe.rotation.x = Math.PI / 2;
    pipe.position.set(px * 2.35, py * 2.35, midZ + 0.5 + pipeLen / 2);
    group.add(pipe);
  }
  // Purple backstop: a half-ring wall 1.25 in tall on the wall side of the top.
  const back = new THREE.Mesh(new THREE.CylinderGeometry(holeR + 0.35, holeR + 0.35, f.backstopHeight, 32, 1, true, -Math.PI / 2, Math.PI), m.purple);
  back.rotation.x = Math.PI / 2;
  back.position.z = topZ + f.backstopHeight / 2;
  back.material = m.purple;
  group.add(back);
  // Owner indicator (not on the real FLOWER): a thin ring the sim colors by owner.
  const owner = new THREE.Mesh(new THREE.TorusGeometry(2.75, 0.14, 8, 48), new THREE.MeshStandardMaterial({
    color: 0xd49a2e, emissive: 0x000000, roughness: 0.4 }));
  owner.position.z = topZ + 0.02;
  owner.name = 'topRing';
  group.add(owner);
  return group;
}

// -----------------------------------------------------------------------------
// HIVE frame (§9.6.1, Fig 9-8): brushed aluminum square tube A-frames, base
// rails, a crossbar at the pivot height and the yellow BIOBUZZ banner.
// -----------------------------------------------------------------------------
function tube(a, b, w, mat) {
  const dir = b.clone().sub(a);
  const len = dir.length();
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, len, w), mat);
  mesh.position.copy(a).add(b).multiplyScalar(0.5);
  mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.normalize());
  return mesh;
}

function buildHiveFrame(h, m) {
  const group = new THREE.Group();
  const hx = h.frameWidth / 2;
  const hy = h.frameDepth / 2;
  const top = h.pivotHeight;
  const V = (x, y, z) => new THREE.Vector3(x, y, z);
  for (const sx of [-1, 1]) {
    for (const sy of [-1, 1]) {
      group.add(tube(V(sx * hx, sy * hy, 0.5), V(sx * hx, 0, top), 1.0, m.alu));
      const foot = new THREE.Mesh(new THREE.BoxGeometry(2.4, 2.4, 0.35), m.blackPlastic);
      foot.position.set(sx * hx, sy * hy, 0.18);
      group.add(foot);
    }
    group.add(tube(V(sx * hx, -hy, 0.5), V(sx * hx, hy, 0.5), 1.0, m.alu)); // base rail along y
    const cap = new THREE.Mesh(new THREE.BoxGeometry(2.2, 2.6, 2.6), m.blackPlastic); // apex bracket
    cap.position.set(sx * hx, 0, top);
    group.add(cap);
  }
  for (const sy of [-1, 1]) group.add(tube(V(-hx, sy * hy, 0.5), V(hx, sy * hy, 0.5), 0.8, m.aluDark));
  group.add(tube(V(-hx, 0, top), V(hx, 0, top), 1.5, m.alu)); // crossbar
  // Banner under the crossbar, both faces.
  for (const sy of [-1, 1]) {
    const banner = new THREE.Mesh(new THREE.PlaneGeometry(22, 3.4), m.banner);
    banner.position.set(0, sy * 0.8, top - 2.6);
    banner.rotation.x = Math.PI / 2;
    if (sy > 0) banner.rotation.y = Math.PI;
    group.add(banner);
  }
  return group;
}

// -----------------------------------------------------------------------------
// One HIVE (§9.6.2, Figs 9-9 to 9-11): an aluminum arm on the pivot with a
// CELL at each end. Each CELL: a pentagon prism of clear polycarbonate held
// by alliance-colored flat-bar frames with rounded corners, AprilTags on the
// bottom (§9.9), open at the outer end. The group rotates when it tips.
// -----------------------------------------------------------------------------
function offsetPolygon(pts, d) {
  // Push each vertex out along the bisector so every edge moves out by d (convex polygon, CCW).
  const n = pts.length;
  const out = [];
  for (let i = 0; i < n; i++) {
    const p0 = pts[(i + n - 1) % n];
    const p1 = pts[i];
    const p2 = pts[(i + 1) % n];
    const e1 = [p1[0] - p0[0], p1[1] - p0[1]];
    const e2 = [p2[0] - p1[0], p2[1] - p1[1]];
    const n1 = norm([e1[1], -e1[0]]);
    const n2 = norm([e2[1], -e2[0]]);
    const k = d / (1 + n1[0] * n2[0] + n1[1] * n2[1]);
    out.push([p1[0] + (n1[0] + n2[0]) * k, p1[1] + (n1[1] + n2[1]) * k]);
  }
  return out;
}

function norm(v) {
  const l = Math.hypot(v[0], v[1]);
  return [v[0] / l, v[1] / l];
}

/** Closed path through the polygon with its corners rounded (radius r). */
function roundedPath(path, pts, r) {
  const n = pts.length;
  for (let i = 0; i < n; i++) {
    const p0 = pts[(i + n - 1) % n];
    const p1 = pts[i];
    const p2 = pts[(i + 1) % n];
    const a = norm([p0[0] - p1[0], p0[1] - p1[1]]);
    const b = norm([p2[0] - p1[0], p2[1] - p1[1]]);
    const s = [p1[0] + a[0] * r, p1[1] + a[1] * r];
    const e = [p1[0] + b[0] * r, p1[1] + b[1] * r];
    if (i === 0) path.moveTo(s[0], s[1]); else path.lineTo(s[0], s[1]);
    path.quadraticCurveTo(p1[0], p1[1], e[0], e[1]);
  }
  path.closePath();
  return path;
}

function buildHive(g, side, m, q) {
  const h = g.hive;
  const group = new THREE.Group();
  const x = (side === 'RED' ? -1 : 1) * h.hiveCenterToCenter / 2;
  group.position.set(x, 0, h.pivotHeight);
  const redUpIsAudience = h.redInitialUpCell === 'audience';
  const upIsMinusY = (side === 'RED') === redUpIsAudience;
  group.rotation.x = (upIsMinusY ? -1 : 1) * h.tiltDegrees * DEG;

  const w = h.cellOpeningWidth / 2;
  const base = g.elementPhysics.cellBaseOffset;      // same number as the physics (Hive.java)
  const rectTop = base + h.cellOpeningRectHeight;
  const peak = base + h.cellOpeningHeight;
  const opening = [[-w, base], [w, base], [w, rectTop], [0, peak], [-w, rectTop]]; // CCW in (x, z)
  const inner = h.cellGap / 2;
  const depth = h.cellDepth;
  const frameMat = side === 'RED' ? m.frameRed : m.frameBlue;

  // Flat-bar frame: outline 0.9 in outside the opening, rounded corners, 0.25 in thick.
  const outer = offsetPolygon(opening, 0.9);
  const frameShape = roundedPath(new THREE.Shape(), outer, 1.1);
  frameShape.holes.push(roundedPath(new THREE.Path(), opening, 0.6));
  const frameGeo = new THREE.ExtrudeGeometry(frameShape, { depth: 0.25, bevelEnabled: false, curveSegments: 8 });
  frameGeo.rotateX(Math.PI / 2); // shape (x, z-up); extrusion along -y

  // Polycarbonate skin: the 5 side faces plus the closed inner end.
  const skinGeo = (y0, y1) => {
    const pos = [];
    for (let i = 0; i < 5; i++) {
      const [ax, az] = opening[i];
      const [bx, bz] = opening[(i + 1) % 5];
      pos.push(ax, y0, az, bx, y0, bz, bx, y1, bz, ax, y0, az, bx, y1, bz, ax, y1, az);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    geo.computeVertexNormals();
    return geo;
  };
  const endShape = new THREE.Shape(opening.map(([u, v]) => new THREE.Vector2(u, v)));

  const tags = g.aprilTags;
  const prefix = side === 'RED' ? 'red' : 'blue';
  for (const dir of [-1, 1]) {
    const y0 = dir * inner;
    const y1 = dir * (inner + depth);
    for (const y of [y0, y1]) {
      const f = new THREE.Mesh(frameGeo, frameMat);
      f.position.y = y + 0.125; // centered on y (extrusion goes toward -y)
      group.add(f);
    }
    // Longitudinal bars at the 5 corners.
    for (const [u, v] of opening) {
      const bar = tube(new THREE.Vector3(u, y0, v), new THREE.Vector3(u, y1, v), 0.35, frameMat);
      group.add(bar);
    }
    const skin = new THREE.Mesh(skinGeo(y0, y1), m.cellPanel);
    skin.renderOrder = 3;
    group.add(skin);
    const endGeo = new THREE.ShapeGeometry(endShape);
    endGeo.rotateX(Math.PI / 2);
    const end = new THREE.Mesh(endGeo, m.cellPanel);
    end.position.y = y0;
    end.renderOrder = 3;
    group.add(end);
    // Mounting tab under the CELL floor (the triangle at the bottom of Fig 9-11).
    const tab = new THREE.Mesh(new THREE.BoxGeometry(6, depth * 0.6, 0.6), frameMat);
    tab.position.set(0, (y0 + y1) / 2, base - 0.5);
    group.add(tab);
    // AprilTags on the bottom face, facing down (§9.9), same layout as Hive.aprilTags().
    const ids = tags[prefix + (dir < 0 ? 'AudienceCellIds' : 'FarCellIds')];
    const offs = [-tags.clusterSpacingOuter, -tags.clusterSpacingInner, tags.clusterSpacingInner, tags.clusterSpacingOuter];
    offs.forEach((o, i) => {
      const id = ids[i];
      if (!m.tagCache.has(id)) {
        m.tagCache.set(id, new THREE.MeshStandardMaterial({ map: T.aprilTagTexture(id), roughness: 0.7 }));
      }
      const tag = new THREE.Mesh(new THREE.PlaneGeometry(tags.size, tags.size), m.tagCache.get(id));
      tag.position.set(o * (side === 'RED' ? -1 : 1), (y0 + y1) / 2, base - 0.03);
      tag.rotation.x = Math.PI; // face down
      group.add(tag);
    });
  }
  // The arm under the CELLS, the pivot hub and the two dampers (Fig 9-9).
  const armLen = h.hiveLength;
  const arm = new THREE.Mesh(new THREE.BoxGeometry(2.2, armLen, 0.5), m.alu);
  arm.position.z = base - 1.15;
  group.add(arm);
  const hub = new THREE.Mesh(new THREE.CylinderGeometry(1.3, 1.3, 2.8, 24), m.aluDark);
  hub.rotation.z = Math.PI / 2;
  group.add(hub);
  for (const dy of [-1, 1]) {
    const brace = tube(new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, dy * 5, base - 1.0), 0.8, m.alu);
    group.add(brace);
    const damper = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.45, 1.4, 16), m.blackPlastic);
    damper.position.set(0, dy * 3.2, -0.2);
    group.add(damper);
  }
  return group;
}

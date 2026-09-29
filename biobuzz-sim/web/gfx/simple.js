// =============================================================================
// simple.js - the original, lightweight look (Quality: Low).
// Flat Lambert materials, no shadows. Kept exactly as it was so slow laptops
// stay smooth. The realistic models live in field.js and robots.js.
// =============================================================================
import * as THREE from 'three';

const DEG = Math.PI / 180;

export const COLORS = {
  tile: 0xd6d9dd,        // light gray foam tiles
  tileSeam: 0xb4b8be,
  floorOutside: 0x1c2026,
  wall: 0xcfd8e2,
  wallRail: 0x6f757d,
  cellPanel: 0xe9eef4,   // clear polycarbonate CELL panels
  oursBody: 0x26292e,    // our robot is drawn dark so it stands out
  red: 0xe5484d,
  blue: 0x3e8ef7,
  flowerPipe: 0x3d9a3d,
  flowerRing: 0xd9a441,
  frame: 0xa0a4aa,
  ours: 0xf2c230,
};

export function line(points, material) {
  const geo = new THREE.BufferGeometry().setFromPoints(points.map(p => new THREE.Vector3(...p)));
  return new THREE.Line(geo, material);
}

/** A taped zone: translucent fill plus a solid outline. */
export function tapeRect(r, color, opacity, z) {
  const group = new THREE.Group();
  const w = r.xMax - r.xMin;
  const h = r.yMax - r.yMin;
  const fill = new THREE.Mesh(new THREE.PlaneGeometry(w, h),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity, depthWrite: false }));
  fill.position.set((r.xMin + r.xMax) / 2, (r.yMin + r.yMax) / 2, z);
  group.add(fill);
  group.add(line([[r.xMin, r.yMin, z + 0.01], [r.xMax, r.yMin, z + 0.01], [r.xMax, r.yMax, z + 0.01],
                  [r.xMin, r.yMax, z + 0.01], [r.xMin, r.yMin, z + 0.01]], new THREE.LineBasicMaterial({ color })));
  return group;
}

/** A FLOWER (§9.7, Figure 9-12): four pipes, top ring, middle ring, bottom ring. */
export function makeFlower(x, y, f) {
  const group = new THREE.Group();
  group.position.set(x, y, 0);
  const pipeMat = new THREE.MeshLambertMaterial({ color: COLORS.flowerPipe });
  const ringMat = new THREE.MeshLambertMaterial({ color: COLORS.flowerRing });
  const topZ = f.topOpeningHeight;
  const midZ = f.bottomRingHeight + f.retrievalOpeningHeight;
  const ringR = f.topOpeningDiameter / 2 + 0.4;
  for (let i = 0; i < 4; i++) {
    const a = (i + 0.5) * Math.PI / 2;
    const pipe = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.42, topZ - midZ, 12), pipeMat);
    pipe.rotation.x = Math.PI / 2; // cylinders are Y-up in Three.js; stand them on Z
    pipe.position.set(Math.cos(a) * ringR, Math.sin(a) * ringR, (topZ + midZ) / 2);
    group.add(pipe);
  }
  for (const [z, r] of [[topZ, ringR], [midZ, ringR], [f.bottomRingHeight / 2, f.bottomRingHoleDiameter / 2 + 0.6]]) {
    const ring = new THREE.Mesh(new THREE.TorusGeometry(r, 0.45, 8, 24), z === topZ ? ringMat.clone() : ringMat);
    ring.position.z = z;
    if (z === topZ) ring.name = 'topRing'; // recolored to show the FLOWER's owner
    group.add(ring);
  }
  return group;
}

/** The HIVE frame (§9.6.1, Figure 9-8): two triangles joined by a crossbar at the pivot height. */
export function makeHiveFrame(h) {
  const group = new THREE.Group();
  const mat = new THREE.MeshLambertMaterial({ color: COLORS.frame });
  const hx = h.frameWidth / 2;
  const hy = h.frameDepth / 2;
  const top = h.pivotHeight;
  for (const sx of [-1, 1]) {
    for (const sy of [-1, 1]) {
      group.add(beam(new THREE.Vector3(sx * hx, sy * hy, 0), new THREE.Vector3(sx * hx, 0, top), 0.75, mat));
    }
  }
  group.add(beam(new THREE.Vector3(-hx, 0, top), new THREE.Vector3(hx, 0, top), 0.9, mat));
  return group;
}

/**
 * One HIVE (§9.6.2, Figures 9-9 to 9-11): an arm on the pivot with a CELL at
 * each end, tilted 30 degrees so one CELL faces up.
 * Shape is approximate (good enough to see and aim at); stage 4 adds the
 * exact CELL openings used for scoring.
 */
export function makeHive(h, side) {
  const color = side === 'RED' ? COLORS.red : COLORS.blue;
  const group = new THREE.Group();
  const x = (side === 'RED' ? -1 : 1) * h.hiveCenterToCenter / 2;
  group.position.set(x, 0, h.pivotHeight);
  // Red's audience-side (-y) CELL starts up (§10.3.1); blue is the 180-degree rotation (+y up).
  const redUpIsAudience = h.redInitialUpCell === 'audience';
  const upIsMinusY = (side === 'RED') === redUpIsAudience;
  group.rotation.x = (upIsMinusY ? -1 : 1) * h.tiltDegrees * DEG;

  // Pentagon cross-section of a CELL (Figure 9-11), in the plane across the arm.
  const w = h.cellOpeningWidth / 2;
  const base = -1.4; // APPROXIMATE: bottom of the CELL below the arm axis
  const shape = new THREE.Shape();
  shape.moveTo(-w, base);
  shape.lineTo(w, base);
  shape.lineTo(w, base + h.cellOpeningRectHeight);
  shape.lineTo(0, base + h.cellOpeningHeight);
  shape.lineTo(-w, base + h.cellOpeningRectHeight);
  shape.closePath();

  const inner = h.cellGap / 2;
  const depth = h.cellDepth;
  const wallMat = new THREE.MeshLambertMaterial({ color: COLORS.cellPanel, transparent: true, opacity: 0.35, side: THREE.DoubleSide, depthWrite: false });
  const rimMat = new THREE.MeshLambertMaterial({ color });
  const pentagon = [[-w, base], [w, base], [w, base + h.cellOpeningRectHeight], [0, base + h.cellOpeningHeight], [-w, base + h.cellOpeningRectHeight]];
  for (const dir of [-1, 1]) {
    const geo = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: false });
    geo.rotateX(Math.PI / 2); // shape up -> z, extrusion -> -y
    geo.translate(0, dir > 0 ? inner + depth : -inner, 0);
    group.add(new THREE.Mesh(geo, wallMat));
    // Thick alliance-colored rims around both ends of the CELL, like the real aluminum frame.
    for (const y of [dir * inner, dir * (inner + depth)]) {
      for (let i = 0; i < 5; i++) {
        const [u1, v1] = pentagon[i];
        const [u2, v2] = pentagon[(i + 1) % 5];
        group.add(beam(new THREE.Vector3(u1, y, v1), new THREE.Vector3(u2, y, v2), 0.55, rimMat));
      }
    }
    for (const [u, v] of pentagon) {
      group.add(beam(new THREE.Vector3(u, dir * inner, v), new THREE.Vector3(u, dir * (inner + depth), v), 0.3, rimMat));
    }
  }
  // The arm joining the two CELLS.
  group.add(beam(new THREE.Vector3(0, -inner, 0), new THREE.Vector3(0, inner, 0), 0.7,
    new THREE.MeshLambertMaterial({ color: COLORS.frame })));
  return group;
}

/** A round beam from point a to point b. */
export function beam(a, b, radius, material) {
  const len = a.distanceTo(b);
  const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, len, 10), material);
  mesh.position.copy(a).add(b).multiplyScalar(0.5);
  mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), b.clone().sub(a).normalize());
  return mesh;
}

/**
 * A robot model. Other robots: alliance-colored body on a bumper plate, with
 * "I" or "II" on top (robot 1 or 2 of its alliance) and a yellow intake bar
 * on the front. Ours: dark body with a shooter tower, so it's easy to spot.
 * (Just for looks - collisions use the simple rectangle from robot.jsonc.)
 */
export function makeRobot(r) {
  const group = new THREE.Group();
  const color = r.alliance === 'RED' ? COLORS.red : COLORS.blue;
  const bumperH = 2.5;
  const bumper = new THREE.Mesh(new THREE.BoxGeometry(r.l, r.w, bumperH), new THREE.MeshLambertMaterial({ color }));
  bumper.position.z = bumperH / 2 + 0.3;
  group.add(bumper);

  const bodyH = r.ours ? r.ht * 0.55 : r.ht * 0.45;
  const bodyColor = r.ours ? COLORS.oursBody : new THREE.Color(color).multiplyScalar(0.8).getHex();
  const body = new THREE.Mesh(new THREE.BoxGeometry(r.l - 2, r.w - 2, bodyH), new THREE.MeshLambertMaterial({ color: bodyColor }));
  body.position.z = bumperH + 0.3 + bodyH / 2;
  group.add(body);
  const topZ = bumperH + 0.3 + bodyH;

  // Yellow intake bar across the front (the robot's +x side).
  const intake = new THREE.Mesh(new THREE.BoxGeometry(1.5, r.w - 3, 2.2), new THREE.MeshLambertMaterial({ color: COLORS.ours }));
  intake.position.set(r.l / 2 + 0.4, 0, bumperH + 1.5);
  group.add(intake);

  if (r.ours) {
    // Shooter tower: base block plus a flywheel housing pointing up.
    const base = new THREE.Mesh(new THREE.CylinderGeometry(3.2, 4.5, 3, 20), new THREE.MeshLambertMaterial({ color: 0x3a3e45 }));
    base.rotation.x = Math.PI / 2;
    base.position.set(-1.5, 0, topZ + 1.5);
    group.add(base);
    const barrel = new THREE.Mesh(new THREE.CylinderGeometry(1.4, 1.6, r.ht - topZ - 1, 16), new THREE.MeshLambertMaterial({ color: 0x55606c }));
    barrel.rotation.x = Math.PI / 2;
    barrel.position.set(-1.5, 0, topZ + 3 + (r.ht - topZ - 4) / 2);
    group.add(barrel);
    const outline = new THREE.LineSegments(new THREE.EdgesGeometry(bumper.geometry), new THREE.LineBasicMaterial({ color: COLORS.ours }));
    outline.position.copy(bumper.position);
    group.add(outline);
  } else {
    // "I" or "II" marks on top, like the reference picture's robot plates.
    const second = /2|PARTNER/.test(r.id);
    const markMat = new THREE.MeshBasicMaterial({ color: 0xffffff });
    const count = second ? 2 : 1;
    for (let i = 0; i < count; i++) {
      const mark = new THREE.Mesh(new THREE.BoxGeometry(r.l * 0.45, 1.2, 0.2), markMat);
      mark.position.set(0, (i - (count - 1) / 2) * 2.6, topZ + 0.1);
      mark.rotation.z = 25 * DEG;
      group.add(mark);
    }
  }
  const label = textSprite(r.ours ? 'US' : r.id, 0, 0, r.ht + 6, '#ffffff', 14);
  label.name = 'label';
  group.add(label);
  return group;
}

/** A text label that always faces the camera. */
export function textSprite(text, x, y, z, color, sizeIn) {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  const px = 64;
  ctx.font = `bold ${px}px -apple-system, sans-serif`;
  canvas.width = Math.ceil(ctx.measureText(text).width) + 16;
  canvas.height = px + 16;
  ctx.font = `bold ${px}px -apple-system, sans-serif`;
  ctx.fillStyle = color;
  ctx.textBaseline = 'middle';
  ctx.fillText(text, 8, canvas.height / 2);
  const tex = new THREE.CanvasTexture(canvas);
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthTest: false, transparent: true }));
  const aspect = canvas.width / canvas.height;
  sprite.scale.set(sizeIn * aspect * 0.5, sizeIn * 0.5, 1);
  sprite.position.set(x, y, z);
  return sprite;
}

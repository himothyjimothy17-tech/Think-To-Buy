// =============================================================================
// cad.js - optional real CAD models.
//
//   web/models/field.glb   -> drawn instead of the generated field
//   web/models/robot.glb   -> drawn as OUR robot
//   web/models/models.json -> how to line them up (scale, rotation, offset,
//                             which generated parts to keep, part names)
//
// If a file is missing, the generated model is used. See README "Using real CAD".
// =============================================================================
import * as THREE from 'three';
import { GLTFLoader } from '../vendor/GLTFLoader.js';

const DEG = Math.PI / 180;

/**
 * Defaults. glTF is Y-up and in METERS; the sim is Z-up and in INCHES, so the
 * usual setting is scale 39.37 and a +90 deg turn about X.
 */
export const CAD_DEFAULTS = {
  field: {
    file: 'models/field.glb',
    scale: 39.3701,
    rotationDeg: [90, 0, 0],
    offset: [0, 0, 0],
    // Generated parts to keep drawing on top of the CAD field. The HIVES tip
    // during a match, so keep ours (and hide the CAD HIVE CELLS with hideParts).
    keepGenerated: { hives: true, tiles: false, walls: false, flowers: false, hiveFrame: false },
    hideParts: [],
  },
  robot: {
    file: 'models/robot.glb',
    scale: 39.3701,
    rotationDeg: [90, 0, 0],
    offset: [0, 0, 0],
    // Part names (or regular expressions) to animate. Leave empty to guess from names.
    wheels: { FL: '', BL: '', FR: '', BR: '' },
    flywheel: '',
    intake: [],
    gate: '',
    // Axis each part spins around, in the ROBOT frame after lining up: x, y or z.
    wheelAxis: 'y',
    flywheelAxis: 'y',
    intakeAxis: 'y',
    gateAxis: 'y',
  },
};

let configPromise = null;

/** Reads web/models/models.json (optional) merged over the defaults. */
export function cadConfig() {
  if (!configPromise) {
    configPromise = fetchJson('models/models.json').then(user => ({
      field: { ...CAD_DEFAULTS.field, ...(user?.field || {}),
        keepGenerated: { ...CAD_DEFAULTS.field.keepGenerated, ...(user?.field?.keepGenerated || {}) } },
      robot: { ...CAD_DEFAULTS.robot, ...(user?.robot || {}),
        wheels: { ...CAD_DEFAULTS.robot.wheels, ...(user?.robot?.wheels || {}) } },
    }));
  }
  return configPromise;
}

let listPromise = null;

/**
 * The simulator lists web/models/ at "models/" so we can check for a file
 * without a 404 in the console. If the page is served some other way (no
 * listing), fall back to asking for the file itself.
 */
async function exists(url) {
  if (!listPromise) {
    listPromise = fetch('models/', { cache: 'no-store' })
      .then(r => (r.ok ? r.json() : null))
      .then(list => (Array.isArray(list) ? list : null))
      .catch(() => null);
  }
  const list = await listPromise;
  if (list) return list.includes(url.replace(/^models\//, ''));
  try {
    const r = await fetch(url, { method: 'HEAD', cache: 'no-store' });
    return r.ok;
  } catch (e) {
    return false;
  }
}

async function fetchJson(url) {
  if (!(await exists(url))) return null;
  try {
    return JSON.parse(stripComments(await (await fetch(url, { cache: 'no-store' })).text()));
  } catch (e) {
    console.warn('models.json is not valid JSON:', e);
    return null;
  }
}

// Removes // line and /* block comments (outside strings), so models.json can be JSONC like the config files.
function stripComments(text) {
  let out = '';
  let inStr = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      out += c;
      if (c === '\\') out += text[++i] ?? '';
      else if (c === '"') inStr = false;
    } else if (c === '"') {
      inStr = true;
      out += c;
    } else if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && text[i + 1] === '*') {
      i = text.indexOf('*/', i + 2);
      if (i < 0) break;
      i++;
    } else {
      out += c;
    }
  }
  return out;
}

const cache = new Map();

/** Loads a .glb if it exists; resolves to a fresh copy of its scene or null. */
export async function loadModel(url) {
  if (!cache.has(url)) {
    cache.set(url, (async () => {
      if (!(await exists(url))) return null;
      const gltf = await new GLTFLoader().loadAsync(url);
      return gltf.scene;
    })().catch(e => {
      console.warn('Could not load', url, e);
      return null;
    }));
  }
  const scene = await cache.get(url);
  return scene ? scene.clone(true) : null;
}

/** Wraps a CAD scene in a group that applies scale / rotation / offset from the config. */
export function placed(scene, c) {
  const inner = new THREE.Group();
  inner.add(scene);
  inner.scale.setScalar(c.scale);
  inner.rotation.set(c.rotationDeg[0] * DEG, c.rotationDeg[1] * DEG, c.rotationDeg[2] * DEG);
  const outer = new THREE.Group();
  outer.position.set(c.offset[0], c.offset[1], c.offset[2]);
  outer.add(inner);
  outer.traverse(o => {
    if (o.isMesh) {
      o.castShadow = true;
      o.receiveShadow = true;
    }
  });
  return outer;
}

/** Hides CAD parts whose name matches any pattern (e.g. the CAD HIVE CELLS). */
export function hideParts(root, patterns) {
  const res = patterns.map(p => new RegExp(p, 'i'));
  root.traverse(o => {
    if (o.name && res.some(re => re.test(o.name))) o.visible = false;
  });
}

function findPart(root, nameOrPattern, guesses) {
  const tests = nameOrPattern ? [new RegExp(`^${nameOrPattern}$`, 'i'), new RegExp(nameOrPattern, 'i')] : guesses;
  for (const re of tests) {
    let hit = null;
    root.traverse(o => { if (!hit && o.name && re.test(o.name)) hit = o; });
    if (hit) return hit;
  }
  return null;
}

/**
 * Puts a CAD part under a pivot at its own center so it can spin in place
 * (CAD parts usually have their origin somewhere else). Returns the pivot.
 */
function makeSpinnable(part, robotRoot) {
  robotRoot.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(part);
  const center = box.getCenter(new THREE.Vector3());
  const pivot = new THREE.Group();
  robotRoot.worldToLocal(pivot.position.copy(center));
  robotRoot.add(pivot);
  pivot.updateMatrixWorld(true);
  pivot.attach(part); // keeps the part where it is, now relative to the pivot
  return pivot;
}

/**
 * Builds our robot from robot.glb and finds the moving parts. The animation
 * handles have the same shape as the generated robot's, so the same
 * animateOurs() drives them. Returns null if there is no robot.glb.
 */
export async function buildCadRobot(c, servoCfg, hoodMode) {
  const scene = await loadModel(c.file);
  if (!scene) return null;
  const root = new THREE.Group();
  root.add(placed(scene, c));
  const anim = { wheels: [], rollers: [], flyAxle: null, flySharp: new THREE.Group(), flyBlur: new THREE.Group(),
    gate: null, hood: null, hoodMode, servo: servoCfg, axes: {} };
  const guess = key => [new RegExp(`(^|[^a-z])${key}([^a-z]|$)`, 'i')];
  const wheelGuesses = {
    FL: [/front.?left/i, ...guess('fl')], BL: [/back.?left|rear.?left/i, ...guess('bl'), ...guess('rl')],
    FR: [/front.?right/i, ...guess('fr')], BR: [/back.?right|rear.?right/i, ...guess('br'), ...guess('rr')],
  };
  const found = [];
  for (const k of ['FL', 'BL', 'FR', 'BR']) {
    const p = findPart(root, c.wheels[k], wheelGuesses[k].map(re => new RegExp(`wheel.*${re.source}|${re.source}.*wheel`, 'i')));
    const pivot = p ? spinner(makeSpinnable(p, root), c.wheelAxis) : new THREE.Group();
    anim.wheels.push(pivot);
    found.push(k + (p ? '=' + p.name : ' not found'));
  }
  const fly = findPart(root, c.flywheel, [/flywheel/i, /shooter.?wheel/i]);
  if (fly) anim.flyAxle = spinner(makeSpinnable(fly, root), c.flywheelAxis);
  const intakeNames = c.intake.length ? c.intake : [];
  const intakeParts = [];
  if (intakeNames.length) intakeNames.forEach(n => { const p = findPart(root, n, []); if (p) intakeParts.push(p); });
  else root.traverse(o => { if (o.name && /intake.?roller|roller.?intake/i.test(o.name)) intakeParts.push(o); });
  for (const p of intakeParts) anim.rollers.push(spinner(makeSpinnable(p, root), c.intakeAxis));
  const gate = findPart(root, c.gate, hoodMode ? [/hood/i] : [/gate/i, /feeder/i]);
  if (gate) {
    const pv = spinner(makeSpinnable(gate, root), c.gateAxis);
    if (hoodMode) anim.hood = pv; else anim.gate = pv;
  }
  console.info('robot.glb parts:', found.join(', '), '| flywheel', fly ? fly.name : 'not found',
    '| intake rollers', intakeParts.length, '| gate/hood', gate ? gate.name : 'not found');
  root.userData.anim = anim;
  return root;
}

/**
 * The animation code turns parts about their local Y axis. For a CAD part
 * whose spin axis is X or Z, add a helper so "rotation.y" means the right axis.
 */
function spinner(pivot, axis) {
  if (axis === 'y') return pivot;
  const holder = new THREE.Group();
  holder.position.copy(pivot.position);
  pivot.parent.add(holder);
  pivot.position.set(0, 0, 0);
  // Tilt a frame so its Y axis lies along the requested axis, then counter-tilt the part.
  if (axis === 'x') holder.rotation.z = -Math.PI / 2; else holder.rotation.x = Math.PI / 2;
  holder.add(pivot);
  const inner = new THREE.Group();
  if (axis === 'x') inner.rotation.z = Math.PI / 2; else inner.rotation.x = -Math.PI / 2;
  while (pivot.children.length) inner.add(pivot.children[0]);
  pivot.add(inner);
  return pivot;
}

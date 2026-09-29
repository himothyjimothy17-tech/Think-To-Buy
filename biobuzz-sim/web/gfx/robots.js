// =============================================================================
// robots.js - realistic robot models (Quality: Medium / High).
//
// OUR robot is built from config/robot.jsonc: chassis size, wheel diameter,
// track width, wheelbase, intake roller and flywheel sizes, camera mount,
// shooter servo mode (gate or hood). Parts that move are kept as separate
// objects so animate() can spin them from the simulator's state.
//
// Robot frame: +x = front (intake), +y = left, +z = up, origin on the floor
// at the robot's center.
// =============================================================================
import * as THREE from 'three';
import * as T from './textures.js';
import { ALLIANCE } from './field.js';

const DEG = Math.PI / 180;
const MM = 1 / 25.4;
const TWO_PI = Math.PI * 2;

/** Materials shared by every robot for one quality level. */
export function makeRobotMaterials(q, fieldMats) {
  const Std = q.physical ? THREE.MeshPhysicalMaterial : THREE.MeshStandardMaterial;
  const holes = T.channelHoleTexture();
  return {
    alu: fieldMats.alu,
    holes,
    channel: (lenIn, widthIn) => {
      const t = holes.clone();
      t.needsUpdate = true;
      t.repeat.set(lenIn / (24 * MM), widthIn / (24 * MM));
      return new Std({ color: 0xc3c7cc, metalness: 1, roughness: 0.4, roughnessMap: fieldMats.alu.roughnessMap,
        alphaMap: t, alphaTest: 0.5, side: THREE.DoubleSide });
    },
    plate: new Std({ color: 0xc3c7cc, metalness: 1, roughness: 0.36, roughnessMap: fieldMats.alu.roughnessMap,
      alphaMap: T.pocketTexture(), alphaTest: 0.5, side: THREE.DoubleSide }),
    black: new Std({ color: 0x1c1d20, metalness: 0.7, roughness: 0.45 }),
    rubber: new THREE.MeshStandardMaterial({ color: 0x151515, roughness: 0.92 }),
    hubPlate: new Std({ color: 0xb9bec4, metalness: 1, roughness: 0.3 }),
    gearbox: new Std({ color: 0xd9a619, metalness: 0.85, roughness: 0.35 }),
    motorCan: new Std({ color: 0x131313, metalness: 0.6, roughness: 0.38 }),
    revHub: new THREE.MeshStandardMaterial({ color: 0x2a2c30, roughness: 0.6, metalness: 0.1 }),
    revLabel: new THREE.MeshStandardMaterial({ color: 0xf07f1d, roughness: 0.5 }),
    battery: new THREE.MeshStandardMaterial({ color: 0x1b1b1b, roughness: 0.7 }),
    batteryLabel: new THREE.MeshStandardMaterial({ color: 0xf2c230, roughness: 0.6 }),
    polycarb: fieldMats.polycarb,
    deck: new THREE.MeshStandardMaterial({ color: 0x202226, roughness: 0.8, metalness: 0.1 }),
    roller: new THREE.MeshStandardMaterial({ map: T.rollerTexture(), roughness: 0.75 }),
    lens: new Std({ color: 0x0b0d10, metalness: 0.2, roughness: 0.05, ...(q.physical ? { clearcoat: 1 } : {}) }),
    led: new THREE.MeshStandardMaterial({ color: 0x3dff6e, emissive: 0x2bd158, emissiveIntensity: 2 }),
    spokes: new THREE.MeshStandardMaterial({ map: T.spokeTexture(), transparent: true, alphaTest: 0.3,
      metalness: 0.6, roughness: 0.35, side: THREE.DoubleSide }),
    blur: new THREE.MeshStandardMaterial({ map: T.blurDiscTexture(), transparent: true, depthWrite: false,
      metalness: 0.4, roughness: 0.4, side: THREE.DoubleSide }),
    gate: new Std({ color: 0xff7a1a, roughness: 0.4, metalness: 0.1 }),
    servo: new THREE.MeshStandardMaterial({ color: 0x111214, roughness: 0.5 }),
    ramp: new Std({ color: 0xdde6ef, roughness: 0.1, transparent: true, opacity: 0.35, depthWrite: false, side: THREE.DoubleSide }),
    panel: {
      RED: new Std({ color: ALLIANCE.red, roughness: 0.45, metalness: 0.1 }),
      BLUE: new Std({ color: ALLIANCE.blue, roughness: 0.45, metalness: 0.1 }),
    },
    signCache: new Map(),
  };
}

function box(w, d, h, mat, x = 0, y = 0, z = 0) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, d, h), mat);
  m.position.set(x, y, z);
  return m;
}

/** Cylinder whose axis runs along robot Y (wheels, rollers, flywheels). */
function cylY(r, len, mat, seg = 24) {
  return new THREE.Mesh(new THREE.CylinderGeometry(r, r, len, seg), mat);
}

/** Cylinder whose axis runs along robot X. */
function cylX(r, len, mat, seg = 20) {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, len, seg), mat);
  m.rotation.z = Math.PI / 2;
  return m;
}

/**
 * goBILDA-style U-channel (48 mm square, 2.5 mm wall) with the hole pattern,
 * running along X, open side facing +y or -y.
 */
function uChannel(len, rm, openToward, x = 0, y = 0, z = 0) {
  const s = 48 * MM;
  const t = 2.5 * MM;
  const g = new THREE.Group();
  const web = box(len, t, s, rm.channel(len, s));
  web.position.y = -openToward * (s / 2 - t / 2);
  g.add(web);
  for (const dz of [-1, 1]) {
    const flange = box(len, s, t, rm.channel(len, s));
    flange.position.z = dz * (s / 2 - t / 2);
    g.add(flange);
  }
  g.position.set(x, y, z);
  return g;
}

/** A goBILDA Yellow Jacket gearmotor, output shaft along +y or -y. */
function gearmotor(rm, lengthIn, toward, gearbox = true) {
  const g = new THREE.Group();
  const gb = gearbox ? 1.4 : 0;
  if (gearbox) {
    const gbx = cylY(0.72, gb, rm.gearbox, 20);
    gbx.position.y = toward * gb / 2;
    g.add(gbx);
  }
  const can = cylY(0.72, lengthIn - gb, rm.motorCan, 20);
  can.position.y = toward * (gb + (lengthIn - gb) / 2);
  g.add(can);
  const cap = cylY(0.55, 0.3, rm.black, 16);
  cap.position.y = toward * (lengthIn + 0.15);
  g.add(cap);
  return g;
}

/**
 * A mecanum wheel: aluminum side plates and rollers at 45 degrees.
 * hand = +1 or -1 picks which way the rollers slant (X pattern seen from above:
 * front-left and back-right are one hand, back-left and front-right the other).
 * The returned group spins about its Y axis.
 */
export function mecanumWheel(radius, width, hand, rm, rollers = 10, detail = true) {
  const g = new THREE.Group();
  const rr = radius * 0.22;              // roller radius
  const rc = radius - rr;                // roller centers
  const rollerLen = width * 1.25;
  const profile = [];
  const steps = detail ? 6 : 3;
  for (let i = 0; i <= steps; i++) {
    const u = i / steps;
    const yy = (u - 0.5) * rollerLen;
    const rad = rr * (0.62 + 0.38 * Math.sin(Math.PI * u)); // barrel shape
    profile.push(new THREE.Vector2(rad, yy));
  }
  const rollerGeo = new THREE.LatheGeometry(profile, detail ? 12 : 8);
  const up = new THREE.Vector3(0, 1, 0);
  for (let i = 0; i < rollers; i++) {
    const phi = (i / rollers) * TWO_PI;
    const tangent = new THREE.Vector3(Math.cos(phi), 0, -Math.sin(phi));
    const dir = tangent.multiplyScalar(Math.SQRT1_2).add(new THREE.Vector3(0, hand * Math.SQRT1_2, 0)).normalize();
    const roller = new THREE.Mesh(rollerGeo, rm.rubber);
    roller.position.set(Math.sin(phi) * rc, 0, Math.cos(phi) * rc);
    roller.quaternion.setFromUnitVectors(up, dir);
    g.add(roller);
  }
  for (const s of [-1, 1]) {
    const side = cylY(radius * 0.8, 0.12, rm.hubPlate, detail ? 28 : 16);
    side.position.y = s * width * 0.42;
    g.add(side);
  }
  const hubCore = cylY(radius * 0.3, width * 0.85, rm.black, 16);
  g.add(hubCore);
  return g;
}

/** Robot sign (G414): alliance color with the team/robot name, on both sides. */
function sign(text, alliance, rm, w = 7.5, h = 2.2) {
  const key = text + alliance;
  if (!rm.signCache.has(key)) {
    const bg = alliance === 'RED' ? '#c8202a' : '#1f5fd6';
    rm.signCache.set(key, new THREE.MeshStandardMaterial({ map: T.signTexture(text, bg), roughness: 0.5 }));
  }
  return new THREE.Mesh(new THREE.PlaneGeometry(w, h), rm.signCache.get(key));
}

// =============================================================================
// OUR ROBOT
// =============================================================================
export function buildOurRobot(r, cfg, rm, q) {
  const root = new THREE.Group();
  const ch = cfg.chassis;
  const L = ch.length;
  const W = ch.width;
  const H = ch.height;
  const R = ch.wheelDiameterMm * MM / 2;
  const track = ch.trackWidth;
  const base = ch.wheelBase;
  const wheelW = 38 * MM;
  const s48 = 48 * MM;
  const anim = { wheels: [], rollers: [], flyAxle: null, flySharp: null, flyBlur: null, gate: null, hood: null,
    hoodMode: cfg.shooter.servoMode === 'hood', servo: cfg.shooter.servo };

  // --- Chassis: two side rails and two cross members of goBILDA U-channel.
  const railY = track / 2 - wheelW / 2 - 0.15 - s48 / 2;
  for (const sy of [-1, 1]) {
    root.add(uChannel(L - 0.6, rm, sy, 0, sy * railY, R));
  }
  for (const sx of [-1, 1]) {
    const cross = uChannel(2 * railY + s48, rm, 1, sx * (L / 2 - 0.3 - s48 / 2), 0, R + s48);
    cross.rotation.z = Math.PI / 2;
    root.add(cross);
  }
  // Deck plate for the electronics (black polycarbonate), rear 2/3 of the robot.
  const deckZ = R + 1.5 * s48 + 0.06;
  root.add(box(L * 0.62, W - 0.6, 0.12, rm.deck, -L * 0.18, 0, deckZ));

  // --- Wheels + drive motors. Order FL, BL, FR, BR (same as the simulator).
  const wheelPos = [[base / 2, track / 2], [-base / 2, track / 2], [base / 2, -track / 2], [-base / 2, -track / 2]];
  const hands = [-1, 1, 1, -1];
  wheelPos.forEach(([wx, wy], i) => {
    const pivot = new THREE.Group();
    pivot.position.set(wx, wy, R);
    const wheel = mecanumWheel(R, wheelW, hands[i], rm, q.rollers, true);
    pivot.add(wheel);
    root.add(pivot);
    anim.wheels.push(wheel);
    const motor = gearmotor(rm, 3.6, -Math.sign(wy));
    motor.position.set(wx, Math.sign(wy) * (railY - s48 / 2), R);
    root.add(motor);
  });

  // --- Intake (front): two compliant-wheel rollers. The lower one fits between
  // the front wheels, the upper one spans the whole mouth above them.
  const ir = cfg.intake.rollerDiameterMm * MM / 2;
  const mouth = cfg.intake.mouthWidth;
  const lowerSpan = 2 * (track / 2 - wheelW / 2 - 0.35);
  const rollerDefs = [[L / 2 - ir - 0.2, lowerSpan, ir + 1.0], [L / 2 - ir - 1.6, mouth, 2 * R + ir + 0.6]];
  for (const [rx, span, rz] of rollerDefs) {
    const pivot = new THREE.Group();
    pivot.position.set(rx, 0, rz);
    const roller = cylY(ir, span, rm.roller, 20);
    const shaft = cylY(0.16, span + 0.6, rm.alu, 8);
    const spin = new THREE.Group();
    spin.add(roller, shaft);
    pivot.add(spin);
    root.add(pivot);
    anim.rollers.push(spin);
  }
  // Side plates holding the upper roller + the two 1620 RPM motors on top of them.
  for (const sy of [-1, 1]) {
    const plateY = sy * (mouth / 2 + 0.25);
    root.add(box(3.6, 0.18, 3.0, rm.alu, L / 2 - 2.2, plateY, 2 * R + ir + 0.6));
    const m = gearmotor(rm, 3.0, -sy);
    m.position.set(L / 2 - 2.2, sy * (mouth / 2 + 0.5), 2 * R + 2 * ir + 1.6);
    m.rotation.z = 0;
    root.add(m);
  }
  // Clear ramp from the intake up to the shooter.
  const rampLen = 9.5;
  const ramp = box(rampLen, 6.2, 0.12, rm.ramp);
  ramp.position.set(1.8, 0, deckZ + 3.2);
  ramp.rotation.y = -38 * DEG;
  ramp.renderOrder = 3;
  root.add(ramp);

  // --- Shooter: 2x 6000 RPM motors on one flywheel, 1:1, between two side plates.
  const fr = cfg.shooter.flywheelDiameterMm * MM / 2;
  const flyX = cfg.shooter.exitForward - fr * 0.9;
  const flyZ = cfg.shooter.exitHeight - fr * 1.1;
  const plateGap = 3.9;
  for (const sy of [-1, 1]) {
    const sp = box(7.0, 0.2, flyZ - deckZ + 3.6, rm.plate, flyX - 0.5, sy * plateGap, deckZ + (flyZ - deckZ + 3.6) / 2);
    root.add(sp);
    const mot = gearmotor(rm, 2.6, sy, false);
    mot.position.set(flyX, sy * (plateGap + 0.1), flyZ);
    root.add(mot);
  }
  const flyPivot = new THREE.Group();
  flyPivot.position.set(flyX, 0, flyZ);
  const flyAxle = new THREE.Group();
  const rim = new THREE.Mesh(new THREE.CylinderGeometry(fr, fr, 1.5, 40, 1, true), rm.rubber);
  flyAxle.add(rim);
  const sharp = new THREE.Group();
  const blur = new THREE.Group();
  for (const sy of [-1, 1]) {
    const face = new THREE.Mesh(new THREE.CircleGeometry(fr, 40), rm.spokes);
    face.rotation.x = sy * Math.PI / 2;
    face.position.y = sy * 0.76;
    sharp.add(face);
    const bface = new THREE.Mesh(new THREE.CircleGeometry(fr * 1.01, 40), rm.blur);
    bface.rotation.x = sy * Math.PI / 2;
    bface.position.y = sy * 0.78;
    blur.add(bface);
  }
  flyAxle.add(sharp);
  flyPivot.add(flyAxle, blur, cylY(0.2, 2 * plateGap, rm.alu, 10));
  root.add(flyPivot);
  anim.flyAxle = flyAxle;
  anim.flySharp = sharp;
  anim.flyBlur = blur;
  blur.visible = false;

  // Hood: a curved sheet above the flywheel, the ball rides between them.
  const hoodR = fr + (cfg.shooter.exitSpeedFactor ? 2.9 : 2.9);
  // CylinderGeometry's axis is already Y; theta 0 = straight up (+z), 90 deg = forward (+x).
  // The arc runs from just behind the top to where the ball leaves at ~45 degrees.
  const hoodGeo = new THREE.CylinderGeometry(hoodR, hoodR, 2 * plateGap - 0.4, 28, 1, true, -40 * DEG, 95 * DEG);
  const hoodPivot = new THREE.Group();
  hoodPivot.position.set(flyX, 0, flyZ);
  const hood = new THREE.Mesh(hoodGeo, rm.black);
  hood.material = rm.hoodMat || rm.black;
  hoodPivot.add(hood);
  root.add(hoodPivot);
  anim.hood = anim.hoodMode ? hoodPivot : null;

  // Servo + feeder gate (gate mode) at the top of the ramp, or the hood servo.
  const servoBox = box(1.6, 0.8, 1.6, rm.servo, flyX + 2.6, plateGap - 0.6, flyZ - 2.6);
  root.add(servoBox);
  if (!anim.hoodMode) {
    const gatePivot = new THREE.Group();
    gatePivot.position.set(flyX + 2.6, 0, flyZ - 2.0);
    const paddle = box(0.18, 2 * plateGap - 1.6, 2.6, rm.gate, 0, 0, -1.3);
    gatePivot.add(paddle);
    root.add(gatePivot);
    anim.gate = gatePivot;
  }

  // --- Electronics on the deck: Control Hub (left), Expansion Hub (right), battery (rear).
  const hub = (label) => {
    const g = new THREE.Group();
    g.add(box(5.6, 4.1, 1.0, rm.revHub));
    g.add(box(5.62, 0.3, 0.2, rm.revLabel, 0, -1.6, 0.45));
    for (let i = 0; i < 4; i++) g.add(box(0.35, 0.5, 0.25, rm.black, -2.2 + i * 0.6, 1.7, 0.6)); // motor ports
    g.name = label;
    return g;
  };
  const ctrl = hub('controlHub');
  ctrl.position.set(-L * 0.18, W / 2 - 2.4, deckZ + 0.56);
  root.add(ctrl);
  const exp = hub('expansionHub');
  exp.position.set(-L * 0.18, -(W / 2 - 2.4), deckZ + 0.56);
  root.add(exp);
  const bat = new THREE.Group();
  bat.add(box(1.9, 5.8, 3.2, rm.battery));
  bat.add(box(1.92, 5.0, 0.9, rm.batteryLabel, 0, 0, 0.6));
  bat.position.set(-L / 2 + 1.4, 0, deckZ + 1.66);
  root.add(bat);

  // --- Limelight 3A where robot.jsonc says the camera is, tilted up.
  const cam = cfg.camera || {};
  const ll = new THREE.Group();
  ll.add(box(0.9, 2.4, 1.9, rm.black));
  const lensMesh = cylX(0.38, 0.12, rm.lens, 20);
  lensMesh.position.set(0.47, 0, 0.1);
  ll.add(lensMesh);
  for (const dy of [-0.8, 0.8]) {
    const led = box(0.05, 0.35, 0.35, rm.led, 0.46, dy, 0.1);
    ll.add(led);
  }
  ll.position.set(Math.min(cam.mountForward ?? 8, L / 2 - 0.5), cam.mountLateral ?? 0, cam.mountHeight ?? 8);
  ll.rotation.y = -(cam.pitchDeg ?? 15) * DEG;
  root.add(ll);
  root.add(box(0.2, 1.2, (cam.mountHeight ?? 8) - (2 * R + ir + 1.0), rm.alu, (cam.mountForward ?? 8) - 0.9, 0,
    (2 * R + ir + 1.0 + (cam.mountHeight ?? 8)) / 2));

  // --- Robot signs on both sides (alliance color, G414).
  for (const sy of [-1, 1]) {
    const s = sign('US', r.alliance, rm);
    s.position.set(-L * 0.18, sy * (W / 2 + 0.02), deckZ + 2.3);
    s.rotation.x = Math.PI / 2;
    s.rotation.y = sy > 0 ? Math.PI : 0;
    root.add(s);
  }

  shadowAll(root);
  root.userData.anim = anim;
  root.userData.size = { L, W, H, R };
  return root;
}

/**
 * Spins our robot's parts from the simulator's speeds (rad/s) over dt seconds.
 * anim: {wheels[4], intake, flywheel}; mech: {servoActual, launchAngle, hood}.
 */
export function animateOurs(root, speeds, mech, dt) {
  const a = root.userData.anim;
  if (!a) return;
  if (speeds) {
    a.wheels.forEach((w, i) => { w.rotation.y += (speeds.wheels[i] || 0) * dt; });
    // + about robot Y moves a roller's underside backward: that pulls a ball in under it.
    for (const r of a.rollers) r.rotation.y += (speeds.intake || 0) * dt;
    const fw = speeds.flywheel || 0;
    if (a.flyAxle) {
      a.flyAxle.rotation.y += fw * dt; // + about Y: the top of the wheel moves forward, launching the ball
      // Above ~8 rev/s the eye (and a 60 Hz screen) can't follow the spokes:
      // show the motion-blurred disc instead, turning slowly so it still "moves".
      const fast = Math.abs(fw) > TWO_PI * 8;
      a.flySharp.visible = !fast;
      a.flyBlur.visible = fast;
      if (fast) a.flyBlur.rotation.y += Math.sign(fw) * 2.5 * dt;
    }
  }
  if (mech) {
    const sv = a.servo;
    if (a.gate) {
      // Servo travel (0..1 of its 300 deg range) swings the gate up and forward.
      const deg = (mech.servoActual - sv.gateClosedPosition) * sv.rangeDeg;
      a.gate.rotation.y = -Math.min(120, Math.max(0, deg)) * DEG;
    }
    if (a.hood) {
      a.hood.rotation.y = -((mech.launchAngle ?? 45) - 45) * DEG;
    }
  }
}

// =============================================================================
// OTHER ROBOTS: simpler, but real-looking: black frame, mecanum wheels,
// alliance-colored side panels with number plates, intake and shooter hood.
// =============================================================================
export function buildOtherRobot(r, cfg, rm, q) {
  const root = new THREE.Group();
  const L = r.l;
  const W = r.w;
  const H = r.ht;
  const R = (cfg.chassis.wheelDiameterMm || 104) * MM / 2;
  const track = W - 2.0;
  const base = L - 4.0;
  const wheels = [];
  const wheelPos = [[base / 2, track / 2], [-base / 2, track / 2], [base / 2, -track / 2], [-base / 2, -track / 2]];
  const hands = [-1, 1, 1, -1];
  wheelPos.forEach(([wx, wy], i) => {
    const pivot = new THREE.Group();
    pivot.position.set(wx, wy, R);
    const wheel = mecanumWheel(R, 1.4, hands[i], rm, 8, false);
    pivot.add(wheel);
    root.add(pivot);
    wheels.push(wheel);
  });
  // Frame: two side rails + cross members, then a deck.
  for (const sy of [-1, 1]) root.add(box(L - 0.6, 1.0, 1.9, rm.black, 0, sy * (track / 2 - 1.3), R));
  for (const sx of [-1, 1]) root.add(box(1.0, track - 2.6, 1.9, rm.black, sx * (L / 2 - 0.8), 0, R + 1.9));
  const deckZ = R + 3.0;
  root.add(box(L - 1.2, W - 1.6, 0.15, rm.deck, 0, 0, deckZ));
  // Alliance-colored side panels (full length) with number plates.
  for (const sy of [-1, 1]) {
    const p = box(L - 1.0, 0.2, H * 0.36, rm.panel[r.alliance], 0, sy * (W / 2 - 0.1), deckZ + H * 0.18 - 0.4);
    root.add(p);
    const s = sign(r.id, r.alliance, rm, 8.5, 2.3);
    s.position.set(0, sy * (W / 2 + 0.02), deckZ + H * 0.2);
    s.rotation.x = Math.PI / 2;
    s.rotation.y = sy > 0 ? Math.PI : 0;
    root.add(s);
  }
  // Front intake roller and a shooter tower with hood.
  const roller = cylY(0.9, W - 3.2, rm.roller, 14);
  roller.position.set(L / 2 - 1.0, 0, R + 1.6);
  root.add(roller);
  root.add(box(6.0, 6.0, H * 0.45, rm.black, -1.5, 0, deckZ + H * 0.225));
  const hood = new THREE.Mesh(new THREE.CylinderGeometry(3.2, 3.2, 5.6, 20, 1, true, 0, Math.PI), rm.aluDark || rm.alu);
  hood.rotation.x = Math.PI / 2;
  hood.position.set(-1.5, 0, deckZ + H * 0.45);
  root.add(hood);
  // A little electronics on top so it doesn't look like a box.
  root.add(box(4.0, 3.0, 0.8, rm.revHub, -L / 2 + 3.2, W / 2 - 3.2, deckZ + 0.5));
  shadowAll(root);
  root.userData.wheels = wheels;
  root.userData.R = R;
  root.userData.track = track;
  root.userData.base = base;
  return root;
}

/**
 * Wheel spin for a robot we only know the motion of (other robots, replays):
 * each wheel's speed from the robot's forward, sideways and turning speed
 * (mecanum inverse kinematics).
 */
export function animateFromMotion(root, vf, vl, omega, dt) {
  const wheels = root.userData.anim ? root.userData.anim.wheels : root.userData.wheels;
  if (!wheels) return;
  const R = root.userData.R || (root.userData.size ? root.userData.size.R : 2);
  const k = ((root.userData.track || 15) + (root.userData.base || 13)) / 2;
  // FL, BL, FR, BR; left strafe (vl > 0) turns FL backward and BL forward.
  const w = [vf - vl - omega * k, vf + vl - omega * k, vf + vl + omega * k, vf - vl + omega * k];
  wheels.forEach((wh, i) => { wh.rotation.y += (w[i] / R) * dt; });
}

function shadowAll(root) {
  root.traverse(o => {
    if (o.isMesh) {
      const transparent = o.material.transparent;
      o.castShadow = !transparent;
      o.receiveShadow = !transparent;
    }
  });
}

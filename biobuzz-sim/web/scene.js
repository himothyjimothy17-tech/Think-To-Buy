// =============================================================================
// scene.js - draws the BIOBUZZ field and robots with Three.js.
//
// Units are INCHES, like the game manual. The world is Z-UP:
//   +x = toward the blue alliance wall, +y = away from the audience, +z = up.
// The simulator (Java) sends positions in this same frame, so nothing needs
// converting except degrees -> radians.
//
// Three quality levels (Settings panel):
//   Low    - the original flat look (gfx/simple.js): fastest.
//   Medium - realistic materials, venue lighting, soft shadows, reflections.
//   High   - Medium plus sharper shadows, more detail, brushed-metal highlights.
// The models themselves live in gfx/field.js, gfx/robots.js and gfx/cad.js.
// =============================================================================
import * as THREE from 'three';
import { OrbitControls } from './vendor/OrbitControls.js';
import { RoomEnvironment } from './vendor/RoomEnvironment.js';
import { mergeGeometries } from './vendor/BufferGeometryUtils.js';
import * as Simple from './gfx/simple.js';
import { makeMaterials, buildRealField } from './gfx/field.js';
import { makeRobotMaterials, buildOurRobot, buildOtherRobot, animateOurs, animateFromMotion } from './gfx/robots.js';
import { cadConfig, loadModel, placed, hideParts, buildCadRobot } from './gfx/cad.js';

const DEG = Math.PI / 180;

/** Quality presets. Low = the original look. */
export const QUALITY = {
  low: { name: 'low', pbr: false, pixelRatio: 1, shadows: 0 },
  medium: { name: 'medium', pbr: true, pixelRatio: 1.5, shadows: 2048, aniso: 8, rollers: 10,
    ballSeg: [20, 14], physical: false, extraLights: false, envIntensity: 0.55, exposure: 1.0 },
  high: { name: 'high', pbr: true, pixelRatio: 2, shadows: 4096, aniso: 16, rollers: 12,
    ballSeg: [32, 22], physical: true, extraLights: true, envIntensity: 0.6, exposure: 1.0 },
};

export class FieldScene {
  constructor(container, quality = 'medium') {
    this.container = container;
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    container.appendChild(this.renderer.domElement);

    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(50, 1, 1, 2000);
    this.camera.up.set(0, 0, 1); // Z is up
    this.camera.position.set(0, -210, 150);

    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.target.set(0, 0, 10);
    this.controls.enableDamping = true;
    this.controls.maxPolarAngle = 88 * DEG;
    this.controls.update();

    this.lightGroup = new THREE.Group();
    this.fieldGroup = new THREE.Group();
    this.zoneGroup = new THREE.Group();
    this.robotGroup = new THREE.Group();
    this.ballGroup = new THREE.Group();
    this.scene.add(this.lightGroup, this.fieldGroup, this.zoneGroup, this.robotGroup, this.ballGroup);

    this.robotMeshes = new Map();   // robot id -> THREE.Group
    this.robotMotion = new Map();   // robot id -> {x, y, h, t} last pose (for wheel spin)
    this.ballMeshes = new Map();    // ball id -> THREE.Mesh
    this.hiveGroups = {};           // 'RED' / 'BLUE' -> THREE.Group (rotates when it tips)
    this.cameraMode = 'orbit';
    this.alliance = 'RED';
    this.lastRobots = [];
    this.buildId = 0;
    this.lastAnimT = null;

    // Path trail of our robot.
    this.trailPoints = [];
    this.trailGeometry = new THREE.BufferGeometry();
    this.trailLine = new THREE.Line(this.trailGeometry, new THREE.LineBasicMaterial({ color: Simple.COLORS.ours }));
    this.scene.add(this.trailLine);

    // Frame-rate counter (shown in the page).
    this.fps = 0;
    // "?fixedres" in the URL turns off automatic resolution (for screenshots).
    this.fixedRes = new URLSearchParams(location.search).has('fixedres');
    this.frames = 0;
    this.fpsSince = performance.now();

    this.setQuality(quality, false);
    window.addEventListener('resize', () => this.resize());
    this.resize();
    this.renderer.setAnimationLoop(() => this.render());
  }

  resize() {
    const w = this.container.clientWidth || 1;
    const h = this.container.clientHeight || 1;
    this.renderer.setSize(w, h);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  // ---------------------------------------------------------------------------
  // Quality
  // ---------------------------------------------------------------------------
  setQuality(name, rebuild = true) {
    const q = QUALITY[name] || QUALITY.medium;
    this.q = q;
    const r = this.renderer;
    r.setPixelRatio(Math.min(window.devicePixelRatio || 1, q.pixelRatio));
    if (q.pbr) {
      r.toneMapping = THREE.ACESFilmicToneMapping;   // film-like highlights instead of harsh clipping
      r.toneMappingExposure = q.exposure;
      r.shadowMap.enabled = true;
      r.shadowMap.type = THREE.PCFSoftShadowMap;      // soft shadow edges
      if (!this.envMap) {
        // Reflections: a studio-like room lit from above, pre-filtered for rough/smooth materials.
        const pmrem = new THREE.PMREMGenerator(r);
        this.envMap = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
        pmrem.dispose();
      }
      this.scene.environment = this.envMap;
      this.scene.environmentIntensity = q.envIntensity;
      this.scene.background = new THREE.Color(0x14171c);
      this.scene.fog = new THREE.Fog(0x14171c, 420, 900);
    } else {
      r.toneMapping = THREE.NoToneMapping;
      r.shadowMap.enabled = false;
      this.scene.environment = null;
      this.scene.background = new THREE.Color(0x151a20);
      this.scene.fog = null;
    }
    this.buildLights();
    this.resize();
    if (rebuild && this.fieldMsg) {
      this.buildField(this.fieldMsg);
      if (this.lastRobots.length) this.updateRobots(this.lastRobots, false);
    }
  }

  /**
   * Low: the original hemisphere + sun. Medium/High: a competition venue -
   * rows of overhead lights (one shadow-casting key light standing in for the
   * truss right above the field, plus fills), and the environment map for
   * reflections on metal and polycarbonate.
   */
  buildLights() {
    clearGroup(this.lightGroup);
    const q = this.q;
    if (!q.pbr) {
      this.lightGroup.add(new THREE.HemisphereLight(0xffffff, 0x555555, 1.4));
      const sun = new THREE.DirectionalLight(0xffffff, 1.3);
      sun.position.set(-80, -120, 250);
      this.lightGroup.add(sun);
      return;
    }
    this.lightGroup.add(new THREE.HemisphereLight(0xe8eef7, 0x2c3036, 0.55));
    const key = new THREE.DirectionalLight(0xfff6ea, 2.1);
    key.position.set(-35, -55, 320);
    key.target.position.set(0, 0, 0);
    key.castShadow = true;
    key.shadow.mapSize.set(q.shadows, q.shadows);
    const s = key.shadow.camera;
    s.left = -95; s.right = 95; s.top = 95; s.bottom = -95; s.near = 150; s.far = 480;
    key.shadow.bias = -0.0004;
    key.shadow.normalBias = 0.04;
    key.shadow.radius = 2.5;
    this.lightGroup.add(key, key.target);
    // Fill lights from the other trusses (no shadows - cheap).
    const fills = q.extraLights ? [[140, 90, 260, 0.7], [-140, 110, 240, 0.5], [60, -170, 220, 0.6]] : [[100, 40, 250, 1.0]];
    for (const [x, y, z, i] of fills) {
      const f = new THREE.DirectionalLight(0xf2f5ff, i);
      f.position.set(x, y, z);
      this.lightGroup.add(f);
    }
    if (q.extraLights) {
      // Overhead can lights: small bright highlights on the balls and aluminum.
      for (const [x, y] of [[-48, -48], [48, -48], [-48, 48], [48, 48]]) {
        const spot = new THREE.SpotLight(0xffffff, 9000, 0, 34 * DEG, 0.6, 2);
        spot.position.set(x, y, 200);
        spot.target.position.set(x * 0.8, y * 0.8, 0);
        this.lightGroup.add(spot, spot.target);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Building the static field (called when a "field" message arrives)
  // ---------------------------------------------------------------------------
  buildField(msg) {
    this.fieldMsg = msg;
    this.alliance = msg.alliance;
    const id = ++this.buildId;
    clearGroup(this.fieldGroup);
    clearGroup(this.zoneGroup);
    clearGroup(this.robotGroup);
    clearGroup(this.ballGroup);
    this.robotMeshes.clear();
    this.robotMotion.clear();
    this.ballMeshes.clear();
    this.ours = null;
    this.hiveGroups = {};
    this.flowerRings = [];
    this.clearTrail();
    const g = msg.game;
    const el = g.elements;
    const q = this.q;

    if (!q.pbr) {
      this.buildSimpleField(msg);
      // POLLEN 2.8 in yellow, NECTAR 3.6 in red/blue (§9.8).
      this.ballGeo = [new THREE.SphereGeometry(el.pollen.diameter / 2, 16, 12),
                      new THREE.SphereGeometry(el.nectar.diameter / 2, 16, 12),
                      new THREE.SphereGeometry(el.nectar.diameter / 2, 16, 12)];
      this.ballMat = [new THREE.MeshLambertMaterial({ color: 0xf2d027 }),
                      new THREE.MeshLambertMaterial({ color: 0xd9363e }),
                      new THREE.MeshLambertMaterial({ color: 0x2f6fdb })];
      return;
    }

    this.mats = makeMaterials(q, this.renderer);
    this.robotMats = makeRobotMaterials(q, this.mats);
    const [ws, hs] = q.ballSeg;
    this.ballGeo = [new THREE.SphereGeometry(el.pollen.diameter / 2, ws, hs),
                    new THREE.SphereGeometry(el.nectar.diameter / 2, ws, hs),
                    new THREE.SphereGeometry(el.nectar.diameter / 2, ws, hs)];
    this.ballMat = this.mats.ball;
    const out = buildRealField(this.fieldGroup, this.zoneGroup, msg, this.mats, q);
    this.hiveGroups = out.hiveGroups;
    this.flowerRings = out.flowerRings;
    mergeStatic(this.fieldGroup, new Set(Object.values(this.hiveGroups)));
    this.addLabels(msg);
    this.fieldGroup.traverse(o => { o.matrixAutoUpdate = false; o.updateMatrix(); });
    // Hives move: keep their transforms live.
    for (const h of Object.values(this.hiveGroups)) h.traverse(o => { o.matrixAutoUpdate = true; });

    // Real CAD field, if web/models/field.glb exists: swap it in (async).
    cadConfig().then(async c => {
      const scene = await loadModel(c.field.file);
      if (!scene || id !== this.buildId) return;
      const keep = c.field.keepGenerated;
      clearGroup(this.fieldGroup);
      clearGroup(this.zoneGroup);
      const cad = placed(scene, c.field);
      hideParts(cad, c.field.hideParts);
      this.fieldGroup.add(cad);
      const kept = buildRealField(this.fieldGroup, this.zoneGroup, msg, this.mats, q, {
        tiles: keep.tiles, walls: keep.walls, flowers: keep.flowers, hiveFrame: keep.hiveFrame, hives: keep.hives });
      this.hiveGroups = kept.hiveGroups;
      this.flowerRings = kept.flowerRings;
      this.addLabels(msg);
      console.info('Using web/models/field.glb for the field');
    });
  }

  buildSimpleField(msg) {
    const g = msg.game;
    const size = g.field.size;
    const half = size / 2;
    const C = Simple.COLORS;
    const outside = new THREE.Mesh(new THREE.PlaneGeometry(size + 140, size + 60),
      new THREE.MeshLambertMaterial({ color: C.floorOutside }));
    outside.position.z = -0.6;
    this.fieldGroup.add(outside);
    const tiles = new THREE.Mesh(new THREE.BoxGeometry(size, size, g.field.tileThickness),
      new THREE.MeshLambertMaterial({ color: C.tile }));
    tiles.position.z = -g.field.tileThickness / 2;
    this.fieldGroup.add(tiles);
    const seamMat = new THREE.LineBasicMaterial({ color: C.tileSeam });
    for (let i = 1; i < g.field.tilesPerSide; i++) {
      const p = -half + i * g.field.tileSize;
      this.fieldGroup.add(Simple.line([[p, -half, 0.02], [p, half, 0.02]], seamMat));
      this.fieldGroup.add(Simple.line([[-half, p, 0.02], [half, p, 0.02]], seamMat));
    }
    const wallH = g.field.wallHeight;
    const wallMat = new THREE.MeshLambertMaterial({ color: C.wall, transparent: true, opacity: 0.22, depthWrite: false });
    const railMat = new THREE.MeshLambertMaterial({ color: C.wallRail });
    for (const [x, y, w, d] of [[0, half + 0.5, size + 2, 1], [0, -half - 0.5, size + 2, 1],
                                 [half + 0.5, 0, 1, size + 2], [-half - 0.5, 0, 1, size + 2]]) {
      const wall = new THREE.Mesh(new THREE.BoxGeometry(w, d, wallH), wallMat);
      wall.position.set(x, y, wallH / 2);
      this.fieldGroup.add(wall);
      const rail = new THREE.Mesh(new THREE.BoxGeometry(w, d, 1), railMat);
      rail.position.set(x, y, wallH);
      this.fieldGroup.add(rail);
    }
    for (const [alliance, zones] of Object.entries(msg.zones)) {
      const color = alliance === 'red' ? C.red : C.blue;
      this.zoneGroup.add(Simple.tapeRect(zones.loadingZone, color, 0.35, 0.03));
      this.zoneGroup.add(Simple.tapeRect(zones.garden, color, 0.8, 0.03));
      this.zoneGroup.add(Simple.tapeRect(zones.allianceArea, color, 0.12, -0.5));
    }
    for (const f of msg.flowers) {
      const fl = Simple.makeFlower(f.x, f.y, g.flower);
      this.flowerRings.push(fl.getObjectByName('topRing'));
      this.fieldGroup.add(fl);
    }
    this.fieldGroup.add(Simple.makeHiveFrame(g.hive));
    for (const side of ['RED', 'BLUE']) {
      const hive = Simple.makeHive(g.hive, side);
      this.hiveGroups[side] = hive;
      this.fieldGroup.add(hive);
    }
    this.addLabels(msg);
  }

  addLabels(msg) {
    const half = msg.game.field.size / 2;
    for (const s of [Simple.textSprite('AUDIENCE', 0, -half - 18, 1, '#8b95a3', 26),
                     Simple.textSprite('RED', -half - 40, 0, 1, '#e5484d', 26),
                     Simple.textSprite('BLUE', half + 40, 0, 1, '#3e8ef7', 26)]) {
      s.material.toneMapped = false;
      this.fieldGroup.add(s);
    }
  }

  setZonesVisible(v) { this.zoneGroup.visible = v; }
  setTrailVisible(v) { this.trailLine.visible = v; }

  clearTrail() {
    this.trailPoints = [];
    this.trailGeometry.setFromPoints([]);
  }

  // ---------------------------------------------------------------------------
  // Moving things (called for each "state" message, ~60 times a second)
  // ---------------------------------------------------------------------------
  makeRobotMesh(r) {
    let mesh;
    if (!this.q.pbr) {
      mesh = Simple.makeRobot(r);
    } else {
      const cfg = this.fieldMsg.robot;
      mesh = r.ours ? buildOurRobot(r, cfg, this.robotMats, this.q) : buildOtherRobot(r, cfg, this.robotMats, this.q);
      // Join the parts that never move relative to the robot (fewer draw calls).
      const a = mesh.userData.anim;
      const moving = a ? [...a.wheels, ...a.rollers, a.flyAxle, a.flySharp, a.flyBlur, a.gate, a.hood] : [];
      const wheels = a ? a.wheels : (mesh.userData.wheels || []);
      mergeStatic(mesh, new Set([...moving, ...wheels].filter(Boolean)));
      for (const w of wheels) mergeStatic(w, new Set()); // each wheel spins as one piece
      const label = Simple.textSprite(r.ours ? 'US' : r.id, 0, 0, r.ht + 6, '#ffffff', 12);
      label.name = 'label';
      label.material.toneMapped = false;
      label.material.opacity = 0.85;
      mesh.add(label);
      if (r.ours) this.tryCadRobot(r, mesh);
    }
    return mesh;
  }

  /** If web/models/robot.glb exists, it replaces our generated robot (same animations). */
  tryCadRobot(r, generated) {
    const id = this.buildId;
    cadConfig().then(async c => {
      const cfg = this.fieldMsg.robot;
      const cad = await buildCadRobot(c.robot, cfg.shooter.servo, cfg.shooter.servoMode === 'hood');
      if (!cad || id !== this.buildId || this.robotMeshes.get(r.id) !== generated) return;
      cad.position.copy(generated.position);
      cad.rotation.copy(generated.rotation);
      const label = generated.getObjectByName('label');
      if (label) cad.add(label);
      this.robotGroup.remove(generated);
      this.robotGroup.add(cad);
      this.robotMeshes.set(r.id, cad);
      console.info('Using web/models/robot.glb for our robot');
    });
  }

  /**
   * robots: [{id, alliance, ours, x, y, h, l, w, ht}]. t = sim time (s), used to
   * spin wheels of robots we only see moving (other robots, replays).
   */
  updateRobots(robots, addTrail = true, t = null) {
    this.lastRobots = robots;
    for (const r of robots) {
      let mesh = this.robotMeshes.get(r.id);
      if (!mesh) {
        mesh = this.makeRobotMesh(r);
        this.robotMeshes.set(r.id, mesh);
        this.robotGroup.add(mesh);
      }
      mesh.position.set(r.x, r.y, 0);
      mesh.rotation.z = r.h * DEG;
      if (r.ours) {
        this.ours = r;
        if (addTrail) this.addTrailPoint(r.x, r.y);
      }
      // Wheel spin from motion (ours uses the real wheel speeds instead when live).
      if (this.q.pbr && t !== null && (!r.ours || !this.liveAnim)) {
        const last = this.robotMotion.get(r.id);
        if (last && t > last.t && t - last.t < 0.5) {
          const dt = t - last.t;
          const h = r.h * DEG;
          const dx = (r.x - last.x) / dt;
          const dy = (r.y - last.y) / dt;
          const vf = dx * Math.cos(h) + dy * Math.sin(h);
          const vl = -dx * Math.sin(h) + dy * Math.cos(h);
          let dh = (r.h - last.h) * DEG;
          dh = Math.atan2(Math.sin(dh), Math.cos(dh));
          animateFromMotion(mesh, vf, vl, dh / dt, dt);
        }
        this.robotMotion.set(r.id, { x: r.x, y: r.y, h: r.h, t });
      }
    }
  }

  /**
   * Our robot's moving parts from the live state: s.ours.anim (wheel, intake
   * and flywheel speeds in rad/s) and s.ours.mech (servo, launch angle).
   */
  updateOurMechanisms(ours, t) {
    this.liveAnim = !!(ours && ours.anim);
    if (!this.liveAnim) this.lastAnimT = null;
    if (!this.liveAnim || !this.q.pbr || !this.ours) return;
    const mesh = this.robotMeshes.get(this.ours.id);
    if (!mesh) return;
    let dt = 0;
    if (this.lastAnimT !== null && t > this.lastAnimT && t - this.lastAnimT < 0.5) dt = t - this.lastAnimT;
    this.lastAnimT = t;
    animateOurs(mesh, ours.anim, ours.mech, dt);
  }

  /** Balls: [id, kind, state, x, y, z] (kind 0 pollen / 1 red / 2 blue; state 1 = held). */
  updateBalls(balls) {
    const seen = new Set();
    for (const [id, kind, state, x, y, z] of balls) {
      let m = this.ballMeshes.get(id);
      if (!m) {
        m = new THREE.Mesh(this.ballGeo[kind], this.ballMat[kind]);
        m.castShadow = this.q.pbr;
        m.receiveShadow = this.q.pbr;
        // Each ball gets its own random orientation so the holes don't all line up.
        m.rotation.set(id * 1.7, id * 2.3, id * 0.9);
        this.ballMeshes.set(id, m);
        this.ballGroup.add(m);
      }
      m.position.set(x, y, z);
      m.visible = state !== 1; // held balls are inside the robot
      seen.add(id);
    }
    for (const [id, m] of this.ballMeshes) {
      if (!seen.has(id)) m.visible = false; // left the field
    }
  }

  /** HIVE tilt from the simulator (degrees; + raises the far end). */
  updateHives(hives) {
    for (const h of hives) {
      const g = this.hiveGroups[h.alliance];
      if (g) g.rotation.x = h.angle * DEG;
    }
  }

  /** Colors each FLOWER's owner ring (top-most NECTAR, §10.5.2). */
  updateFlowers(flowers) {
    if (!this.flowerRings) return;
    flowers.forEach((f, i) => {
      const ring = this.flowerRings[i];
      if (!ring) return;
      const C = Simple.COLORS;
      const c = f.owner === 'RED' ? C.red : f.owner === 'BLUE' ? C.blue : (this.q.pbr ? 0xd49a2e : C.flowerRing);
      ring.material.color.setHex(c);
      if (ring.material.emissive) ring.material.emissive.setHex(f.owner ? c : 0x000000);
    });
  }

  /**
   * Comparison trails: our robot's path in saved runs, drawn over the field.
   * paths = [{ points: [[x, y], ...], color: 0xRRGGBB }, ...]
   */
  setCompareTrails(paths) {
    if (this.compareGroup) this.scene.remove(this.compareGroup);
    this.compareGroup = new THREE.Group();
    paths.forEach((p, i) => {
      const pts = p.points.map(([x, y]) => new THREE.Vector3(x, y, 0.5 + i * 0.2));
      const g = new THREE.BufferGeometry().setFromPoints(pts);
      this.compareGroup.add(new THREE.Line(g, new THREE.LineBasicMaterial({ color: p.color })));
    });
    this.scene.add(this.compareGroup);
  }

  addTrailPoint(x, y) {
    const last = this.trailPoints[this.trailPoints.length - 1];
    if (last && Math.hypot(last.x - x, last.y - y) < 0.5) return;
    this.trailPoints.push(new THREE.Vector3(x, y, 0.3));
    if (this.trailPoints.length > 20000) this.trailPoints.shift();
    this.trailGeometry.setFromPoints(this.trailPoints);
  }

  /** Robot name labels: hidden when the camera is close (they would fill the screen). */
  updateLabels() {
    for (const [id, mesh] of this.robotMeshes) {
      const label = mesh.getObjectByName('label');
      if (!label) continue;
      const ours = this.ours && id === this.ours.id;
      const d = this.camera.position.distanceTo(mesh.position);
      label.visible = !(ours && this.cameraMode === 'chase') && d > 100;
    }
  }

  setCameraMode(mode) {
    this.cameraMode = mode;
    if (mode === 'chase' && this.ours) {
      const h = this.ours.h * DEG;
      this.camera.position.set(this.ours.x - Math.cos(h) * 42, this.ours.y - Math.sin(h) * 42, 30);
    }
    this.controls.enabled = mode === 'orbit';
    if (mode === 'orbit') {
      this.camera.up.set(0, 0, 1);
      this.camera.position.set(0, -210, 150);
      this.controls.target.set(0, 0, 10);
      this.controls.update();
    }
  }

  render() {
    const cam = this.camera;
    const nowMs = performance.now();
    const frameDt = Math.min(1, (nowMs - (this.lastFrameMs || nowMs)) / 1000);
    this.lastFrameMs = nowMs;
    const sign = this.alliance === 'BLUE' ? -1 : 1;
    if (this.cameraMode === 'driver') {
      // Standing in our ALLIANCE AREA, eyes about 5 ft up (§9.5: the area is outside the field).
      cam.up.set(0, 0, 1);
      cam.position.set(-sign * 100, 0, 62);
      cam.lookAt(sign * 10, 0, 0);
    } else if (this.cameraMode === 'chase' && this.ours) {
      const h = this.ours.h * DEG;
      const want = new THREE.Vector3(this.ours.x - Math.cos(h) * 42, this.ours.y - Math.sin(h) * 42, 30);
      cam.up.set(0, 0, 1);
      // Follow smoothly, at the same speed whatever the frame rate.
      cam.position.lerp(want, 1 - Math.exp(-9 * frameDt));
      cam.lookAt(this.ours.x + Math.cos(h) * 20, this.ours.y + Math.sin(h) * 20, 6);
    } else if (this.cameraMode === 'top') {
      // Straight down, audience at the bottom of the screen.
      cam.up.set(0, 1, 0);
      cam.position.set(0, 0, 230);
      cam.lookAt(0, 0, 0);
    } else {
      this.controls.update();
    }
    this.updateLabels();
    this.renderer.render(this.scene, cam);
    this.frames++;
    const now = performance.now();
    if (now - this.fpsSince >= 1000) {
      this.fps = this.frames * 1000 / (now - this.fpsSince);
      this.frames = 0;
      this.fpsSince = now;
      this.adaptResolution();
    }
  }

  /**
   * Medium/High on a slow computer: render fewer pixels (down to 60 %) until it
   * runs at 30+ fps, and go back up when there is room. Low never changes.
   */
  adaptResolution() {
    if (!this.q.pbr || document.hidden || this.fixedRes) return;
    const max = Math.min(window.devicePixelRatio || 1, this.q.pixelRatio);
    const min = Math.min(max, 0.6 * (window.devicePixelRatio || 1));
    const now = this.renderer.getPixelRatio();
    let next = now;
    if (this.fps < 28) next = Math.max(min, now * 0.85);
    else if (this.fps > 50) next = Math.min(max, now * 1.1);
    if (Math.abs(next - now) > 0.01) {
      this.renderer.setPixelRatio(next);
      this.resize();
    }
  }
}

// =============================================================================
// Helpers
// =============================================================================

/**
 * Speed: joins the field's many small parts that share a material into one
 * mesh each (hundreds of draw calls -> a few dozen). Parts that move or change
 * color (the HIVES, FLOWER owner rings) are left alone.
 */
function mergeStatic(group, skip) {
  group.updateMatrixWorld(true);
  const toWorld = new THREE.Matrix4().copy(group.matrixWorld).invert();
  const buckets = new Map();
  const walk = o => {
    if (skip.has(o)) return;
    if (o.isMesh && !o.isInstancedMesh && !Array.isArray(o.material) && o.name !== 'topRing' && o.visible) {
      const g = o.geometry;
      const key = [o.material.uuid, Object.keys(g.attributes).sort().join(), g.index ? 1 : 0,
        o.castShadow ? 1 : 0, o.receiveShadow ? 1 : 0, o.renderOrder].join('|');
      if (!buckets.has(key)) buckets.set(key, []);
      buckets.get(key).push(o);
    }
    o.children.forEach(walk);
  };
  group.children.forEach(walk);
  for (const list of buckets.values()) {
    if (list.length < 2) continue;
    const geos = list.map(o => o.geometry.clone().applyMatrix4(new THREE.Matrix4().multiplyMatrices(toWorld, o.matrixWorld)));
    const merged = mergeGeometries(geos, false);
    geos.forEach(g => g.dispose());
    if (!merged) continue;
    const m = new THREE.Mesh(merged, list[0].material);
    m.castShadow = list[0].castShadow;
    m.receiveShadow = list[0].receiveShadow;
    m.renderOrder = list[0].renderOrder;
    for (const o of list) {
      o.parent.remove(o);
      o.geometry.dispose();
    }
    group.add(m);
  }
}

function clearGroup(group) {
  while (group.children.length) {
    const c = group.children.pop();
    c.traverse(o => {
      if (o.geometry) o.geometry.dispose();
      if (o.material) {
        (Array.isArray(o.material) ? o.material : [o.material]).forEach(m => {
          for (const k of ['map', 'bumpMap', 'alphaMap', 'roughnessMap']) if (m[k]) m[k].dispose();
          m.dispose();
        });
      }
    });
  }
}

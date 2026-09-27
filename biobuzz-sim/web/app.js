// =============================================================================
// app.js - connects the page to the simulator (WebSocket on 127.0.0.1) and
// keeps the panels up to date.
//
// Messages FROM the simulator:
//   {type:"field", ...}  once per connection and after Reset: field layout, OpModes, settings
//   {type:"state", ...}  ~60 times a second: robots, match clock, telemetry, log
// Messages TO the simulator:
//   {type:"gamepad", index:1, lx, ly, ...}        controller state
//   {type:"cmd", cmd:"init", opmode:"..."}         Driver Station buttons and settings
// =============================================================================
import { FieldScene } from './scene.js';
import { GamepadInput, pressedButtons } from './input.js';

const $ = id => document.getElementById(id);
const GAMEPAD_SEND_MS = 20;      // send controller state up to 50 times a second
const GAMEPAD_HEARTBEAT_MS = 250; // ...and at least 4 times a second even if nothing changed

const scene = new FieldScene($('viewport'));
const input = new GamepadInput();
let socket = null;
let fieldMsg = null;
let lastGamepadJson = '';
let lastGamepadSent = 0;
let paused = false;
const phaseNames = { PRE_MATCH: 'PRE-MATCH', AUTO: 'AUTO', TRANSITION: 'TRANSITION', TELEOP: 'TELEOP', POST_MATCH: 'MATCH OVER' };

// ---------------------------------------------------------------- connection

function connect() {
  socket = new WebSocket(`ws://${location.host}/ws`);
  socket.onopen = () => {
    $('connection').textContent = 'Connected';
    $('connection').classList.add('ok');
    $('status-line').textContent = 'Java simulator connected · localhost only';
  };
  socket.onclose = () => {
    $('connection').textContent = 'Disconnected - is the simulator running? (./gradlew sim) Retrying...';
    $('connection').classList.remove('ok');
    $('status-line').textContent = 'Disconnected';
    setTimeout(connect, 1000);
  };
  socket.onmessage = e => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'field') onField(msg);
    else if (msg.type === 'state') onState(msg);
  };
}

function send(obj) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(obj));
}

function cmd(name, extra = {}) {
  send({ type: 'cmd', cmd: name, ...extra });
}

// ---------------------------------------------------------------- field message

function onField(msg) {
  fieldMsg = msg;
  scene.buildField(msg);

  // OpMode list, grouped like the Driver Station (TeleOp / Autonomous).
  const sel = $('opmode-select');
  const previous = sel.value;
  sel.innerHTML = '';
  for (const auto of [false, true]) {
    const group = document.createElement('optgroup');
    group.label = auto ? 'Autonomous' : 'TeleOp';
    for (const op of msg.opmodes.filter(o => o.autonomous === auto)) {
      const o = document.createElement('option');
      o.value = op.name;
      o.textContent = op.name;
      group.appendChild(o);
    }
    if (group.children.length) sel.appendChild(group);
  }
  if ([...sel.options].some(o => o.value === previous)) sel.value = previous;

  // Full-match pickers: AUTO list and TELEOP list (plus "none").
  for (const [id, auto] of [['match-auto', true], ['match-teleop', false]]) {
    const m = $(id);
    const prev = m.value;
    m.innerHTML = '<option value="">(none)</option>'
      + msg.opmodes.filter(o => o.autonomous === auto).map(o => `<option>${o.name}</option>`).join('');
    if (m.dataset.touched && [...m.options].some(o => o.value === prev)) m.value = prev;
    else if (m.options.length > 1) m.selectedIndex = 1;
  }

  const poses = $('startpose-select');
  poses.innerHTML = '';
  for (const p of msg.startPoses) {
    const o = document.createElement('option');
    o.value = p;
    o.textContent = p;
    poses.appendChild(o);
  }
  poses.value = msg.startPose;

  const label = $('our-alliance');
  label.textContent = `${msg.alliance} ALLIANCE`;
  label.className = `alliance-label ${msg.alliance.toLowerCase()}`;
  $('btn-red').classList.toggle('active', msg.alliance === 'RED');
  $('btn-blue').classList.toggle('active', msg.alliance === 'BLUE');
  $('variants').textContent = msg.variants.length ? `Design variants: ${msg.variants.join(', ')}` : 'Design: robot.jsonc defaults';

  const warn = $('warnings');
  warn.innerHTML = '';
  for (const w of msg.warnings) {
    const li = document.createElement('li');
    li.textContent = w;
    warn.appendChild(li);
  }
  $('warnings-card').hidden = msg.warnings.length === 0;
  buildTunables(msg.tunables || []);
}

// ---------------------------------------------------------------- state message

function onState(s) {
  if (!fieldMsg) return; // wait for the field layout first
  record(s);
  if (viewMode === 'live') {
    scene.updateRobots(s.robots);
    scene.updateBalls(s.balls);
    scene.updateHives(s.hives);
  }
  const ourHive = s.hives.find(h => h.alliance === (fieldMsg ? fieldMsg.alliance : 'RED'));
  if (ourHive) {
    $('hive-tips').textContent = ourHive.tips;
    $('hive-load').textContent = `${ourHive.massG.toFixed(0)} / ${ourHive.tipMassG.toFixed(0)} g`;
  }
  updateScore(s);
  if (viewMode === 'live') scene.updateFlowers(s.flowers);
  $('held').textContent = `${s.ours.mech.held} / 4`;
  $('shots').textContent = `${s.ours.mech.shots} / ${s.ours.mech.pickups}`;
  paused = s.paused;
  $('btn-pause').textContent = paused ? 'Run' : 'Pause';

  if (viewMode === 'live') {
    $('phase').textContent = phaseNames[s.match.phase] || s.match.phase;
    $('timer').textContent = formatClock(s.match.timeLeft);
  }
  $('sim-time').textContent = `sim t = ${s.t.toFixed(2)} s  ·  ${s.speed}x${paused ? '  ·  PAUSED' : ''}`;

  const st = s.opmode.state;
  $('opmode-state').textContent = s.opmode.name ? `${st} (${s.opmode.name})` : st;
  $('btn-start').disabled = st !== 'INIT';
  $('btn-stop').disabled = !(st === 'INIT' || st === 'RUNNING');
  const err = $('opmode-error');
  err.classList.toggle('hidden', !s.opmode.error);
  err.textContent = s.opmode.error || '';

  $('telemetry').textContent = s.telemetry.length ? s.telemetry.join('\n') : '(no telemetry yet)';
  updateRobotPanel(s);

  if (s.log.length) {
    const log = $('log');
    const atBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 5;
    log.textContent += s.log.join('\n') + '\n';
    if (atBottom) log.scrollTop = log.scrollHeight;
  }
}

const BREAKDOWN_ROWS = [
  ['LEAVE', 'leave', 3], ['AUTO PARK', 'autoPark', 5], ['AUTO HIVE tips', 'autoTips', 20],
  ['TELEOP HIVE tips', 'teleopTips', 20], ['Balls in up CELL', 'cellBalls', 2], ['Bottom NECTAR', 'bottomNectar', 5],
  ['Balls in owned FLOWERS', 'flowerBalls', 2], ['GARDEN', 'garden', 1], ['TELEOP PARK', 'teleopPark', 5],
];

function updateScore(s) {
  if (!s.score) return;
  const us = (fieldMsg.alliance || 'RED').toLowerCase();
  const them = us === 'red' ? 'blue' : 'red';
  const a = s.score[us];
  const b = s.score[them];
  $('our-score').textContent = a.total;
  $('their-score').textContent = b.total;
  $('rp').textContent = a.rankingPoints;
  const cell = (x, pts) => `${x}${pts ? ` <span class="subtle">(${x * pts})</span>` : ''}`;
  $('breakdown').innerHTML = `<tr><th></th><th>${us.toUpperCase()}</th><th>${them.toUpperCase()}</th></tr>`
    + BREAKDOWN_ROWS.map(([label, k, p]) => `<tr><td>${label}</td><td>${cell(a[k], p)}</td><td>${cell(b[k], p)}</td></tr>`).join('')
    + `<tr><td>Foul points received</td><td>${a.fouls}</td><td>${b.fouls}</td></tr>`
    + `<tr><td><b>AUTO / TELEOP</b></td><td>${a.auto} / ${a.teleop}</td><td>${b.auto} / ${b.teleop}</td></tr>`
    + `<tr><td><b>Total</b></td><td><b>${a.total}</b></td><td><b>${b.total}</b></td></tr>`
    + `<tr><td colspan="3" class="subtle">${s.score.final ? 'FINAL' : 'end-of-match items shown as if the match ended now'}</td></tr>`;
  const fouls = $('fouls');
  $('fouls-card').hidden = s.fouls.length === 0;
  if (fouls.childElementCount !== s.fouls.length) {
    fouls.innerHTML = s.fouls.map(f => `<li class="${f.penalty.toLowerCase()}">${f.t.toFixed(1)} s · ${f.robot} · `
      + `${f.rule} ${f.penalty}: ${f.what}</li>`).join('');
  }
  $('start-problems').textContent = s.startProblems ? `Illegal start (G304): ${s.startProblems}` : '';
  $('start-problems').hidden = !s.startProblems;
  if (document.activeElement !== $('seed-input')) $('seed-input').value = s.seed;
}

function updateRobotPanel(s) {
  const r = s.robots.find(x => x.ours);
  const o = s.ours;
  const drive = ['drive.frontLeft', 'drive.backLeft', 'drive.frontRight', 'drive.backRight'];
  const motors = Object.fromEntries(o.motors.map(m => [m.role, m]));
  const speed = Math.hypot(o.vx, o.vy);
  const rows = [
    ['Position x, y', `${r.x.toFixed(1)}, ${r.y.toFixed(1)} in`],
    ['Heading', `${r.h.toFixed(1)}°`],
    ['Speed', `${speed.toFixed(1)} in/s`],
    ['Turn rate', `${o.omega.toFixed(0)} °/s`],
    ['Battery', `${o.voltage.toFixed(2)} V (min ${o.minVoltage.toFixed(2)})`],
    ['Battery current', `${o.batteryAmps.toFixed(1)} A`],
  ];
  for (const m of o.motors.filter(m => !drive.includes(m.role))) {
    rows.push([m.name, `${m.power.toFixed(2)} · ${m.rpm.toFixed(0)} rpm · ${m.amps.toFixed(1)} A`]);
  }
  for (const sv of o.servos) {
    rows.push([sv.name, sv.position === null ? 'not set' : sv.position.toFixed(3)]);
  }
  const m = o.mech;
  rows.push(['Flywheel', `${m.flywheelRpm.toFixed(0)} RPM`]);
  rows.push(['Shooter servo (actual)', `${m.servoActual.toFixed(2)} · launch ${m.launchAngle.toFixed(0)}°`]);
  rows.push(['Intake', m.intake + (m.jams ? ` (jams: ${m.jams})` : '')]);
  rows.push(['Held / ball at gate', `${m.held} / ${m.ballAtGate ? 'yes' : 'no'}`]);
  $('robot-state').innerHTML = rows.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('');

  // Wheel power bars (what the hub sends to each drive motor).
  const labels = ['FL', 'BL', 'FR', 'BR'];
  $('wheel-bars').innerHTML = drive.map((role, i) => {
    const m = motors[role];
    const p = m ? m.power : 0;
    const top = p >= 0 ? 50 - p * 50 : 50;
    const height = Math.abs(p) * 50;
    return `<div class="bar"><div class="track"><div class="mid"></div>`
      + `<div class="fill" style="top:${top}%;height:${height}%"></div></div>`
      + `${labels[i]} ${p.toFixed(2)}<br>${m ? m.rpm.toFixed(0) : 0} rpm<br>${m ? m.amps.toFixed(1) : 0} A</div>`;
  }).join('');
}

function formatClock(seconds) {
  const s = Math.ceil(Math.max(0, seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

// ---------------------------------------------------------------- gamepad loop

function pollGamepad() {
  const state = input.read();
  $('gamepad-source').textContent = input.warning ? `${input.source} - ${input.warning}` : input.source;
  $('stick-left').style.transform = `translate(${state.lx * 20}px, ${state.ly * 20}px)`;
  $('stick-right').style.transform = `translate(${state.rx * 20}px, ${state.ry * 20}px)`;
  $('buttons-pressed').textContent = pressedButtons(state);

  const json = JSON.stringify(state);
  const now = performance.now();
  if ((json !== lastGamepadJson && now - lastGamepadSent >= GAMEPAD_SEND_MS) || now - lastGamepadSent >= GAMEPAD_HEARTBEAT_MS) {
    send({ type: 'gamepad', index: 1, ...state });
    lastGamepadJson = json;
    lastGamepadSent = now;
  }
  requestAnimationFrame(pollGamepad);
}

// ---------------------------------------------------------------- buttons

function onClick(id, fn) {
  $(id).addEventListener('click', e => {
    fn(e);
    e.currentTarget.blur(); // so Enter/Space (gamepad keys) don't click it again
  });
}

onClick('btn-init', () => cmd('init', { opmode: $('opmode-select').value }));
onClick('btn-start', () => cmd('start'));
onClick('btn-stop', () => cmd('stop'));
onClick('btn-red', () => cmd('alliance', { value: 'red' }));
onClick('btn-blue', () => cmd('alliance', { value: 'blue' }));
onClick('btn-reset', () => cmd('reset'));
onClick('btn-reload', () => cmd('reload'));
onClick('btn-pause', () => cmd(paused ? 'resume' : 'pause'));
onClick('btn-step', () => cmd('step'));
onClick('btn-clear-trail', () => scene.clearTrail());
onClick('btn-match', () => {
  const seed = parseInt($('seed-input').value, 10);
  if (!Number.isNaN(seed)) cmd('seed', { value: seed });
  scene.clearTrail();
  $('log').textContent = '';
  cmd('startMatch', { auto: $('match-auto').value, teleop: $('match-teleop').value });
});
for (const id of ['match-auto', 'match-teleop']) $(id).addEventListener('change', e => { e.target.dataset.touched = '1'; });
$('startpose-select').addEventListener('change', e => cmd('startPose', { value: e.target.value }));
$('speed-select').addEventListener('change', e => cmd('speed', { value: parseFloat(e.target.value) }));
$('chk-trail').addEventListener('change', e => scene.setTrailVisible(e.target.checked));
$('chk-zones').addEventListener('change', e => scene.setZonesVisible(e.target.checked));
for (const b of document.querySelectorAll('button.cam')) {
  b.addEventListener('click', () => {
    document.querySelectorAll('button.cam').forEach(x => x.classList.remove('active'));
    b.classList.add('active');
    scene.setCameraMode(b.dataset.cam);
    b.blur();
  });
}

// ---------------------------------------------------------------- recording & scrubbing
//
// The page keeps a 10 Hz recording of the live run (same compact format the
// simulator uses in saved runs), so you can drag the timeline back to see
// exactly what happened. Saved runs from runs/ replay through the same code.

let viewMode = 'live';     // 'live' | 'scrub' (looking back at this run) | 'replay' (a saved run)
let liveFrames = [];
let replay = null;          // { frames, robotsInfo, name }
let playing = false;
let lastRecordT = -1;

function frameFromState(s) {
  return {
    t: s.t, phase: s.match.phase, timeLeft: s.match.timeLeft,
    score: s.score ? [s.score.red.total, s.score.blue.total] : [0, 0],
    robots: s.robots.map(r => [r.x, r.y, r.h]),
    balls: s.balls,
    hives: s.hives.map(h => h.angle),
    flowers: s.flowers.map(f => f.owner),
  };
}

function record(s) {
  if (s.t < lastRecordT) liveFrames = []; // the sim was reset
  if (s.match.phase !== 'PRE_MATCH' && s.t - lastRecordT >= 0.099) {
    liveFrames.push(frameFromState(s));
    if (liveFrames.length > 2400) liveFrames.shift();
    lastRecordT = s.t;
  } else if (s.t < lastRecordT) {
    lastRecordT = s.t;
  }
  if (viewMode === 'live') {
    const slider = $('scrub');
    slider.max = Math.max(0, liveFrames.length - 1);
    slider.value = slider.max;
  }
}

function currentFrames() {
  return viewMode === 'replay' ? replay.frames : liveFrames;
}

function robotsInfo() {
  return viewMode === 'replay' ? replay.robotsInfo : fieldMsg.robotsInfo;
}

function showFrame(i) {
  const frames = currentFrames();
  const f = frames[i];
  if (!f) return;
  const info = robotsInfo();
  scene.updateRobots(info.map((r, k) => ({ ...r, x: f.robots[k][0], y: f.robots[k][1], h: f.robots[k][2] })), false);
  scene.updateBalls(f.balls);
  scene.updateHives(f.hives.map((a, k) => ({ alliance: k === 0 ? 'RED' : 'BLUE', angle: a })));
  scene.updateFlowers(f.flowers.map(o => ({ owner: o })));
  $('phase').textContent = (phaseNames[f.phase] || f.phase) + (viewMode === 'replay' ? ' (replay)' : ' (looking back)');
  $('timer').textContent = formatClock(f.timeLeft);
  const label = $('scrub-label');
  label.textContent = `${viewMode === 'replay' ? replay.name + ' · ' : ''}t = ${f.t.toFixed(1)} s · RED ${f.score[0]} - BLUE ${f.score[1]}`;
  label.className = 'tiny subtle ' + viewMode;
}

function goLive() {
  viewMode = 'live';
  playing = false;
  replay = null;
  $('scrub-label').textContent = 'live';
  $('scrub-label').className = 'tiny subtle';
  if (paused) cmd('resume');
}

$('scrub').addEventListener('input', e => {
  if (viewMode === 'live') {
    viewMode = 'scrub';
    cmd('pause'); // freeze the sim while you look back
  }
  showFrame(parseInt(e.target.value, 10));
});
onClick('btn-live', goLive);
onClick('btn-play-replay', () => {
  if (viewMode === 'live') return;
  playing = !playing;
});

let lastPlayMs = 0;
function playLoop(ms) {
  if (playing && viewMode !== 'live' && ms - lastPlayMs > 100) { // 10 Hz = real time
    const slider = $('scrub');
    const next = parseInt(slider.value, 10) + 1;
    if (next >= currentFrames().length) {
      playing = false;
    } else {
      slider.value = next;
      showFrame(next);
    }
    lastPlayMs = ms;
  }
  requestAnimationFrame(playLoop);
}
requestAnimationFrame(playLoop);

// ---------------------------------------------------------------- live tuning

function buildTunables(list) {
  const box = $('tunables');
  if (document.activeElement && box.contains(document.activeElement)) return; // don't fight the user's typing
  box.innerHTML = '';
  for (const t of list) {
    const row = document.createElement('div');
    row.className = 'tune-row';
    const changed = JSON.stringify(t.value) !== JSON.stringify(t.default);
    const label = document.createElement('span');
    label.textContent = t.name;
    label.title = `code value: ${t.default}`;
    if (changed) label.className = 'changed';
    let inputEl;
    if (typeof t.value === 'boolean') {
      inputEl = document.createElement('input');
      inputEl.type = 'checkbox';
      inputEl.checked = t.value;
      inputEl.addEventListener('change', () => cmd('tune', { name: t.name, value: inputEl.checked }));
    } else {
      inputEl = document.createElement('input');
      inputEl.type = typeof t.value === 'number' ? 'number' : 'text';
      inputEl.step = 'any';
      inputEl.value = t.value;
      inputEl.addEventListener('change', () => {
        const v = inputEl.type === 'number' ? parseFloat(inputEl.value) : inputEl.value;
        cmd('tune', { name: t.name, value: v });
      });
    }
    row.append(label, inputEl);
    box.appendChild(row);
  }
}
onClick('btn-tune-reset', () => cmd('tuneReset'));

// ---------------------------------------------------------------- saved runs & comparison

let runFiles = [];
const picked = { A: null, B: null };
const runCache = new Map();

async function loadRun(file) {
  if (!runCache.has(file)) {
    const res = await fetch(`/runs/${encodeURIComponent(file)}`);
    runCache.set(file, await res.json());
  }
  return runCache.get(file);
}

async function refreshRuns() {
  try {
    runFiles = await (await fetch('/runs/')).json();
  } catch (e) {
    runFiles = [];
  }
  const list = $('run-list');
  list.innerHTML = runFiles.length ? '' : '<tr><td class="subtle">(no saved runs yet)</td></tr>';
  for (const r of runFiles) {
    const tr = document.createElement('tr');
    if (picked.A === r.file) tr.className = 'sel-a';
    if (picked.B === r.file) tr.className = 'sel-b';
    const kb = (r.bytes / 1024).toFixed(0);
    tr.innerHTML = `<td>${r.file.replace(/\.json$/, '')} <span class="subtle">${kb} KB</span></td>`
      + `<td><button data-act="play">&#9654;</button> <button data-act="A">A</button> <button data-act="B">B</button></td>`;
    tr.querySelector('[data-act=play]').addEventListener('click', () => startReplay(r.file));
    tr.querySelector('[data-act=A]').addEventListener('click', () => pick('A', r.file));
    tr.querySelector('[data-act=B]').addEventListener('click', () => pick('B', r.file));
    list.appendChild(tr);
  }
}

async function startReplay(file) {
  const run = await loadRun(file);
  if (run.type !== 'biobuzz-run' || !run.frames || !run.frames.length) {
    showCompare(); // headless reports have numbers but no recording
    $('compare').insertAdjacentHTML('afterbegin', '<div class="subtle">That file is a headless batch report (numbers only, no recording) - see the table below.</div>');
    pick('A', file);
    return;
  }
  if (viewMode === 'live' && !paused) cmd('pause');
  replay = { frames: run.frames, robotsInfo: run.robotsInfo, name: run.name };
  viewMode = 'replay';
  const slider = $('scrub');
  slider.max = run.frames.length - 1;
  slider.value = 0;
  showFrame(0);
  playing = true;
}

async function pick(slot, file) {
  picked[slot] = picked[slot] === file ? null : file;
  await refreshRuns();
  await showCompare();
}

/** Numbers we compare, from a saved run's report or a headless batch summary. */
function metricsOf(run) {
  if (run.type === 'biobuzz-headless' && run.rows.length > 1) {
    // A comparison batch: one line per row (mean +/- sd of the score over the same seeds).
    return {
      title: `headless comparison, ${run.seeds.length} seeds (${run.mode})`,
      rows: Object.fromEntries(run.rows.map(r => [r.label, `${r.summary.score.mean.toFixed(1)} ± ${r.summary.score.sd.toFixed(1)}`])),
      path: null,
    };
  }
  if (run.type === 'biobuzz-headless') {
    const row = run.rows[0];
    const m = row.summary;
    return {
      title: `${row.label} (${run.seeds.length} seeds, mean)`,
      rows: Object.fromEntries(Object.entries(m).map(([k, v]) => [k, v.mean])),
      path: null,
    };
  }
  const rep = run.report;
  const us = rep.alliance.toLowerCase();
  const sc = rep.score[us];
  const oursIdx = run.robotsInfo.findIndex(r => r.ours);
  return {
    title: `${run.name} (seed ${rep.seed}${rep.variants.length ? ', ' + rep.variants.join('+') : ''})`,
    rows: {
      'Alliance score': rep.ourScore, 'AUTO points': sc.auto, 'TELEOP points': sc.teleop, 'Opponent score': rep.theirScore,
      'HIVE tips (auto/teleop)': `${sc.autoTips}/${sc.teleopTips}`, 'LEAVE': sc.leave, 'AUTO PARK': sc.autoPark,
      'Balls in up CELL': sc.cellBalls, 'FLOWER balls': sc.flowerBalls, 'Bottom NECTAR': sc.bottomNectar,
      'GARDEN': sc.garden, 'TELEOP PARK': sc.teleopPark, 'Foul pts given away': rep.fouls.filter(f => f.alliance === rep.alliance).reduce((a, f) => a + f.points, 0),
      'Our shots': rep.ourRobot.shots, 'Our shots in CELL': rep.ourRobot.cellEntries, 'Our pickups': rep.ourRobot.pickups,
      'Min battery (V)': rep.ourRobot.minBatteryV, 'Ranking points': sc.rankingPoints,
    },
    path: oursIdx >= 0 ? run.frames.map(f => [f.robots[oursIdx][0], f.robots[oursIdx][1]]) : null,
  };
}

async function showCompare() {
  const out = $('compare');
  const slots = ['A', 'B'].filter(k => picked[k]);
  if (!slots.length) {
    out.innerHTML = '';
    scene.setCompareTrails([]);
    return;
  }
  const ms = [];
  for (const k of slots) ms.push(metricsOf(await loadRun(picked[k])));
  const keys = Object.keys(ms[0].rows);
  const fmt = v => typeof v === 'number' ? (Number.isInteger(v) ? v : v.toFixed(2)) : v;
  let html = '<table><tr><th></th>' + ms.map((m, i) => `<th title="${m.title}">${slots[i]}</th>`).join('')
    + (ms.length === 2 ? '<th>B - A</th>' : '') + '</tr>';
  for (const k of keys) {
    const a = ms[0].rows[k];
    const b = ms.length === 2 ? ms[1].rows[k] : undefined;
    let delta = '';
    if (ms.length === 2 && typeof a === 'number' && typeof b === 'number') {
      const d = b - a;
      delta = `<td class="${d > 0 ? 'delta-up' : d < 0 ? 'delta-down' : ''}">${d > 0 ? '+' : ''}${fmt(d)}</td>`;
    } else if (ms.length === 2) {
      delta = '<td></td>';
    }
    html += `<tr><td>${k}</td><td>${fmt(a)}</td>${ms.length === 2 ? `<td>${b === undefined ? '' : fmt(b)}</td>` : ''}${delta}</tr>`;
  }
  html += '</table>' + ms.map((m, i) => `<div class="subtle">${slots[i]}: ${m.title}</div>`).join('');
  out.innerHTML = html;
  const colors = [0xe5484d, 0x3e8ef7];
  scene.setCompareTrails(ms.map((m, i) => (m.path ? { points: m.path, color: colors[i] } : null)).filter(Boolean));
}

onClick('btn-save-run', () => {
  cmd('saveRun', { value: $('run-name').value || 'run' });
  setTimeout(refreshRuns, 800);
});
onClick('btn-refresh-runs', refreshRuns);
$('runs-box').addEventListener('toggle', e => { if (e.target.open) refreshRuns(); });

connect();
requestAnimationFrame(pollGamepad);

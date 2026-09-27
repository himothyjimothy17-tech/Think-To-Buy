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
}

// ---------------------------------------------------------------- state message

function onState(s) {
  if (!fieldMsg) return; // wait for the field layout first
  scene.updateRobots(s.robots);
  scene.updateBalls(s.balls);
  scene.updateHives(s.hives);
  const ourHive = s.hives.find(h => h.alliance === (fieldMsg ? fieldMsg.alliance : 'RED'));
  if (ourHive) {
    $('hive-tips').textContent = ourHive.tips;
    $('hive-load').textContent = `${ourHive.massG.toFixed(0)} / ${ourHive.tipMassG.toFixed(0)} g`;
  }
  updateScore(s);
  scene.updateFlowers(s.flowers);
  $('held').textContent = `${s.ours.mech.held} / 4`;
  $('shots').textContent = `${s.ours.mech.shots} / ${s.ours.mech.pickups}`;
  paused = s.paused;
  $('btn-pause').textContent = paused ? 'Run' : 'Pause';

  const phaseNames = { PRE_MATCH: 'PRE-MATCH', AUTO: 'AUTO', TRANSITION: 'TRANSITION', TELEOP: 'TELEOP', POST_MATCH: 'MATCH OVER' };
  $('phase').textContent = phaseNames[s.match.phase] || s.match.phase;
  $('timer').textContent = formatClock(s.match.timeLeft);
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

connect();
requestAnimationFrame(pollGamepad);

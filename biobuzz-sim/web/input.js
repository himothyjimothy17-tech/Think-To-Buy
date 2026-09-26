// =============================================================================
// input.js - reads the Logitech F310 (or the keyboard) and produces an FTC
// style gamepad state: sticks from -1 to 1 (stick UP = NEGATIVE y, like the
// real SDK), triggers 0 to 1, and buttons true/false.
//
// F310 NOTE: the switch on the back of the F310 should be on "X". In X mode
// Chrome and Safari usually report the "standard" layout, which we read
// directly. If a browser reports a different layout (for example D mode, or
// macOS without an XInput driver), we use a best guess for the Logitech
// DirectInput layout and show a warning - check the live stick display.
// =============================================================================

const KEY_AXES = {
  // key: [axis, value]
  KeyW: ['ly', -1], KeyS: ['ly', 1], KeyA: ['lx', -1], KeyD: ['lx', 1],
  KeyQ: ['rx', -1], KeyE: ['rx', 1],
};
const KEY_BUTTONS = {
  KeyJ: 'a', KeyK: 'b', KeyU: 'x', KeyI: 'y',
  ShiftLeft: 'rb', ShiftRight: 'rb', KeyZ: 'lb',
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
  Backspace: 'back', Enter: 'start',
};
const KEY_TRIGGERS = { Digit1: 'lt', Digit3: 'rt' };

export class GamepadInput {
  constructor() {
    this.keys = new Set();
    this.source = 'keyboard';
    this.warning = '';
    window.addEventListener('keydown', e => {
      if (isTyping(e)) return;
      if (e.code in KEY_AXES || e.code in KEY_BUTTONS || e.code in KEY_TRIGGERS) {
        e.preventDefault(); // stop arrows/space from scrolling the page
      }
      this.keys.add(e.code);
    });
    window.addEventListener('keyup', e => this.keys.delete(e.code));
    window.addEventListener('blur', () => this.keys.clear()); // don't leave keys "stuck"
  }

  /** Returns the current gamepad-1 state as a plain object. */
  read() {
    const pad = firstGamepad();
    if (pad) {
      this.source = `${pad.id} (${pad.mapping || 'non-standard'} mapping)`;
      this.warning = pad.mapping === 'standard' ? ''
        : 'This controller reports a non-standard button layout. Check the live display below; '
          + 'if it looks wrong, make sure the F310 switch is on X.';
      return pad.mapping === 'standard' ? readStandard(pad) : readLogitechDirectInput(pad);
    }
    this.source = 'keyboard';
    this.warning = '';
    return this.readKeyboard();
  }

  readKeyboard() {
    const s = emptyState();
    for (const code of this.keys) {
      if (KEY_AXES[code]) {
        const [axis, v] = KEY_AXES[code];
        s[axis] = clamp(s[axis] + v);
      }
      if (KEY_BUTTONS[code]) s[KEY_BUTTONS[code]] = true;
      if (KEY_TRIGGERS[code]) s[KEY_TRIGGERS[code]] = 1;
    }
    return s;
  }
}

function firstGamepad() {
  if (!navigator.getGamepads) return null;
  for (const p of navigator.getGamepads()) {
    if (p && p.connected) return p;
  }
  return null;
}

function emptyState() {
  return {
    lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0,
    a: false, b: false, x: false, y: false, lb: false, rb: false,
    back: false, start: false, guide: false, ls: false, rs: false,
    up: false, down: false, left: false, right: false,
  };
}

/** The W3C "standard" gamepad layout (F310 in X mode on most browsers). */
function readStandard(p) {
  const b = i => !!(p.buttons[i] && p.buttons[i].pressed);
  const v = i => (p.buttons[i] ? p.buttons[i].value : 0);
  return {
    lx: axis(p, 0), ly: axis(p, 1), rx: axis(p, 2), ry: axis(p, 3),
    lt: v(6), rt: v(7),
    a: b(0), b: b(1), x: b(2), y: b(3), lb: b(4), rb: b(5),
    back: b(8), start: b(9), ls: b(10), rs: b(11),
    up: b(12), down: b(13), left: b(14), right: b(15), guide: b(16),
  };
}

/**
 * Best guess for the Logitech F310 in D (DirectInput) mode. Triggers are
 * buttons in this mode, and the d-pad is often reported as a "hat" axis.
 */
function readLogitechDirectInput(p) {
  const b = i => !!(p.buttons[i] && p.buttons[i].pressed);
  const s = {
    lx: axis(p, 0), ly: axis(p, 1), rx: axis(p, 2), ry: axis(p, p.axes.length > 5 ? 5 : 3),
    lt: b(6) ? 1 : 0, rt: b(7) ? 1 : 0,
    x: b(0), a: b(1), b: b(2), y: b(3), lb: b(4), rb: b(5),
    back: b(8), start: b(9), ls: b(10), rs: b(11), guide: false,
    up: b(12), down: b(13), left: b(14), right: b(15),
  };
  // Hat switch on the last axis: -1 = up, then clockwise in steps of 2/7.
  const hat = p.axes[9];
  if (hat !== undefined && hat >= -1 && hat <= 1) {
    const i = Math.round((hat + 1) * 3.5);
    s.up = s.up || i === 0 || i === 1 || i === 7;
    s.right = s.right || i === 1 || i === 2 || i === 3;
    s.down = s.down || i === 3 || i === 4 || i === 5;
    s.left = s.left || i === 5 || i === 6 || i === 7;
  }
  return s;
}

function axis(p, i) {
  const v = p.axes[i] || 0;
  return Math.abs(v) < 0.02 ? 0 : clamp(v); // ignore tiny drift at rest
}

function clamp(v) {
  return Math.max(-1, Math.min(1, v));
}

function isTyping(e) {
  const t = e.target;
  return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
}

/** Compact text of which buttons are pressed, for the Gamepad panel. */
export function pressedButtons(s) {
  const names = ['a', 'b', 'x', 'y', 'lb', 'rb', 'back', 'start', 'ls', 'rs', 'up', 'down', 'left', 'right'];
  const out = names.filter(n => s[n]).map(n => n.toUpperCase());
  if (s.lt > 0.05) out.push(`LT ${s.lt.toFixed(2)}`);
  if (s.rt > 0.05) out.push(`RT ${s.rt.toFixed(2)}`);
  return out.join(' ') || '(no buttons)';
}

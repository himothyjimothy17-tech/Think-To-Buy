# BIOBUZZ Simulator

A simulator for the 2026–2027 *FIRST* Tech Challenge game **BIOBUZZ**.
Our real FTC Java OpModes run in it with **zero code changes**, on a field
built from the official game manual.

> **Status:** see [Build stages](#build-stages).

---

## Quick start (Mac)

1. **Install Java 17 or newer** (once):
   ```bash
   brew install openjdk@17
   ```
   (No Homebrew? Download "Temurin 17" from adoptium.net.) Check with `java -version`.
2. **Run the simulator** from the `biobuzz-sim` folder:
   ```bash
   ./gradlew sim
   ```
   The first run downloads Gradle (about 1 minute). Your browser opens
   **http://127.0.0.1:8765/**. Chrome and Safari both work.
3. In the page: choose **Mecanum TeleOp**, press **INIT**, then **▶ START**,
   click the 3D field, and drive.
4. **A full match:** in *Full match*, pick **BIOBUZZ Auto RED** and **Mecanum
   TeleOp**, choose a seed, press **Start match**. AUTO (0:30) → transition
   (0:08) → TELEOP (2:00), scored live, with three computer robots playing.

Everything runs on your own computer (`127.0.0.1`). Nothing is uploaded.

### Driving

| Gamepad (Logitech F310) | Keyboard | Action |
|---|---|---|
| Left stick | W A S D | drive / strafe |
| Right stick X | Q / E | turn |
| Right bumper (hold) | Shift | slow mode |
| Y | I | field-centric ↔ robot-centric |
| Back | Backspace | reset heading |

**F310 on a Mac:** macOS doesn't natively support "X mode" (XInput)
controllers, so the F310 may not show up at all in X mode. Try flipping the
switch on the back to **D**. The Gamepad panel shows what the browser sees.
Check that the sticks and buttons move the right dots before trusting it.

---

## Commands

All commands run from the `biobuzz-sim` folder.

| Command | What it does |
|---|---|
| `./gradlew sim` | Visual simulator in the browser |
| `./gradlew sim --args="--variant hood"` | Same, with a design variant from `config/variants/` |
| `./gradlew headless --args="--auto 'BIOBUZZ Auto RED' --seeds 1-20"` | 20 AUTOs, no graphics, ~40 s; prints mean/sd/min/max, saves JSON in `runs/` |
| `./gradlew headless --args="--auto 'OldAuto\|NewAuto' --seeds 1-20"` | **Old vs new on the same seeds**, with a per-seed paired comparison |
| `./gradlew headless --args="--auto 'BIOBUZZ Auto RED' --variant 'gate\|hood'"` | Design comparison (any `config/variants/` file) |
| `... --set BiobuzzAuto.RPM_SCALE=1.01\|BiobuzzAuto.RPM_SCALE=1.03` | Try TeamCode tunables without editing code |
| `... --teleop 'Mecanum TeleOp'` or `--full` | Whole matches instead of AUTO only |
| `... --ai off` | The other three robots sit still (test our robot alone) |
| `./gradlew sdkCheck` | Checks TeamCode against the **real** FTC SDK (see below) |
| `./gradlew test` | Automated tests (~50, about 2 minutes) |
| `./gradlew :engine:experiment --tests '*ShotMap*' -Pexp.fine=1` | Experiments (shot maps, drive tuning, traces) - print tables, no pass/fail |
| `./gradlew listEstimates` | Lists every guessed number that needs a real measurement |
| `./gradlew listTodos` | Lists every open question and unfinished item |

**Why "the same seeds" matters:** a seed fixes everything random in a match
(ball placement jitter, wheel grip, sensor noise, shot spread, how the other
robots play). Running old and new code on the same 20 seeds means the
difference in score comes from the change, not from luck. The runner prints
"better on N seeds, worse on M" and a 95% confidence interval.

---

## Adding a new OpMode

1. Create a class in `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/`,
   exactly as you would in Android Studio.
2. Put `@TeleOp(name = "...")` or `@Autonomous(name = "...")` on it.
3. Get hardware through `RobotHardware` (so names match the config).
4. Restart `./gradlew sim`. The OpMode appears in the Driver Station list.
5. Run `./gradlew sdkCheck` before copying it to the robot.

**Rules that keep the sim accurate** (the SDK checker enforces them):
- Use `ElapsedTime` for timing, not `System.nanoTime()` or `System.currentTimeMillis()`.
- Use `sleep()` from `LinearOpMode`, not `Thread.sleep()`.
- Don't start your own threads.
- Check `opModeIsActive()` in every loop, or STOP won't work (the sim kills
  OpModes that ignore STOP, like the real SDK does).

### Moving TeamCode to the robot

`TeamCode/` uses the same folder layout and package as the FTC SDK's
`TeamCode` module. Copy the `.java` files into
`FtcRobotController/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/`.
The hardware names in `RobotHardware.java` must match the Driver Station
configuration, which should match `config/hubs.jsonc`.

---

## The SDK check (`./gradlew sdkCheck`)

It downloads the **real** FTC SDK (version 12.0.0 by default; change it with
`-PftcSdkVersion=…`) from Maven Central, then:

1. **Compiles TeamCode against the real SDK.** If this fails, Android Studio would fail too.
2. **Checks the fake SDK against the real one.** Every method in the mock must
   exist in the real SDK, with the same name and parameters.
3. **Lists every SDK method TeamCode uses.**
4. **Flags clock-breaking calls** (`System.nanoTime`, `Thread.sleep`, new threads).

If TeamCode uses an SDK method the mock doesn't have yet, it won't compile in
the sim. The compiler error names the method, and it can be added to `sdk-mock/`.

---

## How it works

```
biobuzz-sim/
  TeamCode/     OUR robot code - plain FTC code, same layout as the FTC SDK
  sdk-mock/     fake FTC SDK: same package names, classes and methods as the real one
  engine/       physics, field, OpMode runner, web server (Java)
  web/          the 3D view (HTML + JavaScript + Three.js, stored locally)
    gfx/        realistic field/robot models, textures, CAD loading
    models/     optional real CAD (.glb), see "Using real CAD"
  config/       game.jsonc (rules), robot.jsonc (our robot), hubs.jsonc (wiring)
  tools/        SDK checker (calibration tools later)
  docs/manual/  the game manual, one image per page
```

### Design decisions (good material for judges)

**1. The physics runs in Java.** The browser only draws what Java sends it.
Our robot code is Java, so the physics, rules and OpMode all run in one
program. The visual mode and the headless mode (Stage 5) use exactly the same
code, so a score from a fast test run means the same as one you watch.

**2. A simulated clock, with the OpMode and physics taking turns ("lockstep").**
On a real robot your OpMode runs on its own thread while the world keeps
moving. If the sim did the same with two free-running threads, results would
depend on how busy your laptop is, and the same test could score differently
each time. Instead:
- Physics advances a simulated clock in fixed **1 ms** steps.
- Every SDK call that takes time on a real Control Hub adds that time to the
  OpMode's clock: an encoder read on the Expansion Hub, `sleep()`, `idle()`.
  The costs are in `config/hubs.jsonc` and are all ESTIMATEs for now.
- When the OpMode's clock gets ahead, it pauses while physics catches up.

**Result:** the same inputs always give exactly the same match (there's a test
for this). Loop times are realistic too: our TeleOp's loop reports about
8 ms, the same order as a real Control Hub without bulk reads. Headless runs
can go as fast as the computer allows.

**3. Red and blue are ROTATED, not mirrored.** The BIOBUZZ field is identical
after a 180° turn around its center (Figures 9-2, 9-17, 10-2). The red
Loading Zone is at the far end of the red wall and the blue one at the
audience end of the blue wall. The config lists only red positions, and blue
ones are computed with `(x, y, heading) → (−x, −y, heading + 180°)`.

**4. Every rule number cites the manual.** `config/game.jsonc` has a manual
section next to every value. Anything not in the manual is labeled
**ESTIMATE** (a guess) or **FIGURE-READ** (read off a drawing), and
`./gradlew listEstimates` lists them all.

**5. The sim's configuration works like the real one.** `config/hubs.jsonc`
plays the role of the Driver Station's "Configure Robot" screen. If
`RobotHardware.java` asks for a name that isn't there, `hardwareMap.get()`
throws the same error as on the robot. The sim also warns at startup and
suggests the closest name.

**6. Motors are mounted like a real mecanum chassis.** The left motors are
physically mirrored (`mountedReversed` in `hubs.jsonc`). If the code forgets
`setDirection(REVERSE)`, the robot spins instead of driving, just like the
real one.

**7. Tuning without editing code.** Any TeamCode `public static` (not
`final`) field - the FTC Dashboard convention - shows up in the sim's **Live
tuning** panel and can be set from the command line (`--set Class.FIELD=v`)
or from a design variant (`"teamcode": {...}`). The same code runs on the
robot, where FTC Dashboard can tune the same fields.

**8. The other three robots are simple but honest.** Each draws a
"personality" from `config/ai.jsonc` (speed, accuracy, launch angle, whether
it parks, etc. - all ESTIMATEs) using the match seed. They shoot with the
same drag physics as ours, so they only score if the ball physics says so,
and they obey the rules (G402 center line in AUTO, max 4 held, no opponent
NECTAR, stop between periods). Our partner follows a pre-match agreement:
it won't shoot from the spots our AUTO uses.

**9. Replays.** Every run is recorded at 10 Hz. Drag the timeline under the
clock to look back (the sim pauses), **Save run** keeps it in `runs/`, and
two saved runs can be compared side by side (scores, robot stats, both paths
drawn on the field).

### Coordinates

Everything uses inches and degrees:
- **Origin:** field center.
- **+x:** toward the **blue** wall.
- **+y:** away from the **audience**.
- **Heading:** 0° faces +x, counter-clockwise is positive.
- **Tiles:** columns A–F run along +x, rows 1–6 along +y (manual §9.4).

### Graphics

**Settings → Graphics quality** picks how the 3D view is drawn (your browser
remembers the choice). The fps counter is next to it.

| Level | What you get | For |
|---|---|---|
| **Low** | The original simple look: flat colors, no shadows | old or slow laptops |
| **Medium** (default) | Realistic materials, venue lighting, soft shadows, reflections | most laptops |
| **High** | Medium plus sharper shadows, clear-coat plastics and more detail | a good graphics card |

On Medium and High, a slow computer automatically draws fewer pixels (down to
60 %) to keep the frame rate up. Add `?fixedres` to the page address to turn
that off.

What you see on Medium and High:
- **Field:** gray foam tiles with puzzle seams, clear polycarbonate walls on
  aluminum rails, brushed-aluminum HIVE frame, see-through pentagon CELLS
  with AprilTags, FLOWERS drawn from Fig 9-12, and wiffle-style balls at their
  real sizes. All textures are drawn in code, so nothing is downloaded.
- **Our robot** is built from `config/robot.jsonc`: goBILDA U-channel with
  holes, 4 mecanum wheels with 45° rollers, Control Hub, Expansion Hub,
  battery, Limelight, 2 intake rollers, 2-motor flywheel and the servo gate
  (or hood).
- **It moves like the sim says.** The wheels turn at their simulated speed,
  the intake rollers spin while they run, and the flywheel turns at its real
  RPM. Above about 480 RPM the flywheel shows a motion-blurred disc, as your
  eye would see it. The gate or hood follows the servo. The state message
  carries `ours.anim` (wheel, intake and flywheel speeds in rad/s) for this.
- **Other robots:** a simpler model with alliance-colored panels, number
  plates, and wheels that spin to match how the robot moves.

### Using real CAD

You can swap in real CAD for the generated models. Put the files in
`web/models/` and reload the page (no restart needed):

| File | Replaces |
|---|---|
| `web/models/field.glb` | the generated field (tiles, walls, FLOWERS, HIVE frame) |
| `web/models/robot.glb` | our generated robot |
| `web/models/models.json` | optional: scale, rotation, offset and part names |

If a file isn't there, the generated model is drawn. The browser console
(F12) says which files were loaded and which moving robot parts were found.

**Getting a .glb**
1. **Field:** FIRST posts the season's field CAD on the FTC game and season
   resources page, usually as an Onshape document and STEP files. Open the
   field assembly in Onshape (make a copy if it's read-only).
2. **Our robot:** open the robot's top-level assembly in Onshape.
3. In Onshape, right-click the assembly tab, choose **Export**, and pick
   **GLTF** with the binary (`.glb`) option. If you only have a STEP file,
   import it into Blender (with a STEP importer add-on) or FreeCAD, then
   choose **File → Export → glTF 2.0 (.glb)**.
4. Keep the files small: under about 50 MB and 1–2 million triangles. Hide
   screws and hardware before exporting, or use Blender's *Decimate*
   modifier. Big files load slowly and lower the frame rate.

**Lining it up.** glTF files are Y-up and in meters. The sim is Z-up and in
inches. The defaults (`scale` 39.37, `rotationDeg` [90, 0, 0]) handle that
conversion, so a model exported with the usual origin often lines up with no
changes. If it doesn't, create `web/models/models.json`:

```jsonc
{
  "field": {
    "scale": 39.3701,           // meters -> inches (use 1 if the file is already in inches)
    "rotationDeg": [90, 0, 0],  // 90 about X turns Y-up into Z-up; the 2nd number turns it about the vertical
    "offset": [0, 0, 0],        // inches, after rotating: +x = blue wall, +y = away from the audience, +z = up
    "keepGenerated": { "hives": true },   // the sim still draws the HIVES (they tip during a match)
    "hideParts": ["cell", "hive.?body"]   // hide the CAD's own HIVE CELLS (names are matched as regex)
  },
  "robot": {
    "scale": 39.3701,
    "rotationDeg": [90, 0, 0],
    "offset": [0, 0, 0],        // inches: move the origin to the robot's center at floor level, front = +x
    "wheels": { "FL": "Wheel FL", "BL": "Wheel BL", "FR": "Wheel FR", "BR": "Wheel BR" },
    "flywheel": "Flywheel",
    "intake": ["Intake Roller 1", "Intake Roller 2"],
    "gate": "Gate",             // or the hood part, for the hood design
    "wheelAxis": "y"            // axis each part spins about, in the robot frame (x, y or z)
  }
}
```

- **Field:** the sim's origin is the center of the field at floor level. Check
  the **Top** camera. The audience should be at the bottom, red on the left.
  The generated alliance zone tape and FLOWER rings are still drawn, so you
  can see whether the CAD field sits on them.
- **Robot:** the sim's robot origin is the center of the robot at floor level,
  facing +x. If the robot faces sideways, change the 2nd rotation number by
  90. Use the **Chase** camera: the intake should point away from you.
- **Moving parts are found by name.** Onshape instance names become the part
  names in the file. Give each wheel a name with "wheel" and its corner in
  it ("Wheel FL", "front left wheel"). Name the other parts "Flywheel",
  "Intake Roller 1" and so on, and "Gate" (or "Hood"). You can also list the
  exact names in `models.json`, which may use `//` comments. Parts that can't
  be found just don't move.

---

## The AUTO (`BiobuzzAuto.java`)

**What it does** (RED; BLUE is the same turned 180°), as a loop:
1. Know which HIVE CELL is up, from the AprilTags under the CELLs
   (`HiveWatcher`): the down CELL's tags hang low (~35 in); when they rise,
   it's tipping. From our first spot the tags vanishing also means a TIP.
2. Holding balls → drive to that CELL's corner (S1 audience corner, S2 far
   corner) and shoot **one at a time**.
3. Otherwise collect: GARDEN (4 POLLEN on the audience wall), the far and
   wall FLOWERS' bottom pockets (4 each), NECTAR the human player puts in our
   LOADING ZONE.
4. Stop launching at 28.5 s so every TIP finishes **before** AUTO ends, then
   PARK in the LOADING ZONE, off the wall (PARK 5 + LEAVE 3).

**Results** (AUTO points for our alliance, the same 20 seeds, other robots
playing - `./gradlew headless --args="--auto 'BIOBUZZ Auto RED' --seeds 1-20"`):

| Version | Change | Mean | SD | Tips/match |
|---|---|---|---|---|
| Leave Auto | drive off the wall | 17 | 11 | 0.4 (partner) |
| v1 | first full plan | 28.0 | 8.9 | 0.70 |
| v2 | wall ride, never switch CELLs without a detected TIP | 33.9 | 2.8 | 1.00 |
| v3 | planner loop; shot spots from the 20/20 plateau | 35.0 | 7.9 | 1.05 |
| v4 | slow sweep + jam recovery (0 jams in 12 seeds) | 35.2 | 5.4 | 1.05 |
| v5 | never feed a CELL not seen to be up; zone NECTAR | 36.2 | 5.5 | 1.10 |
| v8 (final) | NECTAR-aware RPM, pass-through waypoints | **36.2** | 6.4 | **1.10** |
| v8 BLUE | same code, blue alliance | 35.4 | 5.5 | 1.05 |
| v8, robot alone (`--ai off`) | no partner | 28 every seed | 0 | 1.00 |

Zero fouls or warnings by our robot in all of these, and a legal start
(G304) every time (checked by `Stage9Test` for both alliances).

**Honest limits of the "record":**
- The alliance usually gets **one TIP (≈36 points)** and a second TIP in
  ~10% of seeds (51–56 points). A second TIP needs ~250 g in the other CELL,
  which is right at the edge of what one robot can collect and shoot in the
  time left; it happens when our partner helps.
- These numbers rest on ESTIMATEs, above all the **TIP mass (250 g)**, ball
  masses, the CELL shape, the shooter's exit speed and our robot's speed.
  Measure them and re-run; the method stays the same.

**What the simulator taught us (the engineering story):**
1. **Shoot one ball at a time.** Holding the gate open streams balls 0.08 s
   apart and each shot costs ~100 RPM, so the 2nd and 3rd balls leave 4–5%
   slow and bounce off the CELL rim: 1–2 of 4 went in. Feeding one ball only
   when the wheel is back within 40 RPM: **20 of 20** from the best spots.
2. **Find the plateau, not the peak.** The shot map (`ShotMapExperiment`)
   shows a region around (−60, −56) where every nearby spot and RPM ±1% still
   scores 20/20, so small driving errors don't matter.
3. **Stop before you shoot.** Shooting while still settling cost ~25%.
4. **Wall ride + field clamp.** Driving slightly *into* the wall lines the
   robot up for the GARDEN, and odometry can't be past a wall, so pushing into
   it re-zeroes that axis.
5. **Jams are about timing.** The GARDEN balls touch; two in the intake within
   0.12 s jam it. 18 in/s → 1.7 jams/match, 8 in/s → 0.2. We approach fast and
   sweep slow, and after a jam back off so the two balls separate.
6. **Relocalize on AprilTags** when slow: wheel odometry drifted 4–15 in over a
   route, vision kept it within ~1 in.
7. **Know your next ball.** Storage is first-in-first-out and we know what
   each source gives, so the code picks POLLEN or NECTAR RPM for each shot
   without a color sensor.

**Gate vs hood (design comparison):** with the hood at 60° the shot map is
16/16 over a much larger area than the gate's plateau - a better *shooter*.
But in the full AUTO the hood robot scored **29.5 vs 36.2**: with only one
servo the intake must also feed the flywheel in hood mode, so it can't hold
balls while collecting (they fly straight through). **Keep the gate**, unless
the design adds a separate feeder (a second servo or motor); then re-run the
comparison with `--variant hood`.

---

## Robot decisions

The robot isn't built yet. **CONFIRMED** items were decided by the team;
everything else is an **ESTIMATE** in `config/robot.jsonc`.

**Confirmed:**
- Drive: 4× goBILDA Yellow Jacket **435 RPM** motors on mecanum wheels.
- Intake: 2× goBILDA **1620 RPM** motors, front-mounted.
- Shooter: 2× goBILDA **6000 RPM** motors on one flywheel, 1:1.
- 1 servo on the shooter.
- REV Control Hub, 12 V battery, Logitech F310, Limelight 3A.

**Estimated:**
- Size 17 × 17 × 17 in (the R102 starting cube is 18 in), 30 lb, 104 mm wheels.
- Wiring: drive motors on Control Hub ports 0–3; intake and shooter on
  Expansion Hub ports 0–3.
- Flywheel 96 mm at a fixed 45°.
- Servo: a feeder gate by default. Switch to an adjustable hood in `robot.jsonc`,
  or with `--variant hood`.
- Possession sensor off; no odometry pods (Pinpoint support later).

---

## Open questions from the manual (TODO)

1. **How many elements tip a HIVE?** The manual only says "enough" (§8, §9.6).
   The sim uses an estimated 250 g (`elementPhysics.tipMassG`). This one
   number moves the AUTO's score more than anything else - weigh a real HIVE
   setup as soon as one is available and update `BiobuzzAuto.TIP_MASS_G` too.
2. **Ball weights** aren't in the manual. We need to weigh one POLLEN and one NECTAR.
3. **Exact FLOWER and HIVE positions:** read off the figures. §9.1 says exact
   numbers are in the Event Field Setup Guide and the CAD model.
4. **Wall height:** not in the sections we used (estimated 12 in).
5. **Where the 4 pre-staged POLLEN sit inside each FLOWER** (§10.3.1) isn't exact.
6. **Two manual typos:** §10.4 cites a "Table 9-1" that doesn't exist, and
   Figure 10-2 labels a blue-HIVE CELL as "CELL-RED … NECTAR-BLUE".
7. **CELL shape:** the 3D CELLs are approximate (the CELL's bottom sits about
   1.4 in below the pivot arm, worked out from the heights in Fig 9-10). The
   opening shape decides which shots count; check against the CAD model.
8. **Where spilled balls land after a TIP** is a simple guess (the same every
   seed). The AUTO deliberately does NOT depend on it. Film a real TIP.
9. **Human player NECTAR placement** (where in the LOADING ZONE, how fast) is
   an ESTIMATE; the AUTO's zone sweep assumes it lands against the wall.
10. **Achievements during the AUTO→TELEOP transition "may be subject to
    penalties" (§10.5).** The sim logs any TIP that finishes then; our AUTO
    stops launching at 28.5 s so it never happens.

## Not built yet (TODO)

- **Calibration (Stage 7)** is postponed until the robot exists. It will add
  a Calibration OpMode for the real robot plus a tool that fits the sim's
  physics to the logged data.
- **The other robots can't use FLOWER pockets or chase spilled balls
  cleverly**, and they don't play defense.
- **Our robot in TELEOP is only as good as the driver**: there's no AI driver
  for our robot, so full-match batch runs without a TeleOp OpMode only score
  the AUTO part for us.
- **Hood + separate feeder** isn't modeled (see Gate vs hood).

---

## Build stages

| # | Stage | Status |
|---|---|---|
| 1 | SDK mock + field + MecanumTeleOp with gamepad | **done** |
| 2 | Motor physics (torque curves, slip), encoders, IMU noise, battery sag | **done** |
| 3 | POLLEN, NECTAR, intake, shooter, servo, Limelight | **done** |
| 4 | HIVES, FLOWERS, scoring, rule checks, full match flow | **done** |
| 5 | Headless mode, seeds, batch testing, design comparisons | **done** |
| 6 | Other robots (AI), robot-robot pushing | **done** |
| 7 | Calibration tools | postponed until the robot exists |
| 8 | Live tuning panel, saved runs, run comparison, scrubbing | **done** |
| 9 | Legal max-score AUTO, optimized across seeds | **done** |

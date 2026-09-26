# BIOBUZZ Simulator

A simulator for the 2026–2027 *FIRST* Tech Challenge game **BIOBUZZ**.
Our real FTC Java OpModes run in it with **zero code changes**, on a field
built from the official game manual.

> **Status: Stage 1 of 9** - fake FTC SDK, field, and our `MecanumTeleOp`
> driving with a gamepad or keyboard. See [Build stages](#build-stages).

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
| `./gradlew sdkCheck` | Checks TeamCode against the **real** FTC SDK (see below) |
| `./gradlew test` | Automated tests |
| `./gradlew listEstimates` | Lists every guessed number that needs a real measurement |
| `./gradlew listTodos` | Lists every open question and unfinished item |

Headless mode (no graphics, faster than real time, JSON reports) and batch
testing arrive in **Stage 5**.

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

### Coordinates

Everything uses inches and degrees:
- **Origin:** field center.
- **+x:** toward the **blue** wall.
- **+y:** away from the **audience**.
- **Heading:** 0° faces +x, counter-clockwise is positive.
- **Tiles:** columns A–F run along +x, rows 1–6 along +y (manual §9.4).

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
   Stage 4 will model tipping with an estimated weight threshold. We need a
   real number from testing on a field.
2. **Ball weights** aren't in the manual. We need to weigh one POLLEN and one NECTAR.
3. **Exact FLOWER and HIVE positions:** read off the figures. §9.1 says exact
   numbers are in the Event Field Setup Guide and the CAD model.
4. **Wall height:** not in the sections we used (estimated 12 in).
5. **Where the 4 pre-staged POLLEN sit inside each FLOWER** (§10.3.1) isn't exact.
6. **Two manual typos:** §10.4 cites a "Table 9-1" that doesn't exist, and
   Figure 10-2 labels a blue-HIVE CELL as "CELL-RED … NECTAR-BLUE".
7. **CELL shape:** the 3D CELLs are approximate (the CELL's bottom sits about
   1.4 in below the pivot arm, worked out from the heights in Fig 9-10).
   Stage 4 needs the exact openings for scoring.

## Not built yet (TODO)

- **Calibration (Stage 7)** is postponed until the robot exists. It will add
  a Calibration OpMode for the real robot plus a tool that fits the sim's
  physics to the logged data.
- Stages 2–6 and 8–9: see below.

---

## Build stages

| # | Stage | Status |
|---|---|---|
| 1 | SDK mock + field + MecanumTeleOp with gamepad | **done** |
| 2 | Motor physics (torque curves, slip), encoders, IMU noise, battery sag | next |
| 3 | POLLEN, NECTAR, intake, shooter, servo, Limelight | |
| 4 | HIVES, FLOWERS, scoring, rule checks | |
| 5 | Headless mode, seeds, batch testing, design comparisons | |
| 6 | Other robots (AI) | |
| 7 | Calibration tools | postponed until the robot exists |
| 8 | Live tuning panel, run comparison, scrubbing | |
| 9 | Starter autonomous, tested across seeds | |

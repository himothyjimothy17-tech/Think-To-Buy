package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.teamcode.FieldConstants.Pose;

/**
 * BIOBUZZ AUTO. The goal: as many HIVE TIPS (20 points each) as possible in
 * 30 s, plus LEAVE (3) and PARK (5), with zero fouls.
 *
 * THE KEY FACTS (from the manual and the simulator):
 *  - A TIP happens when the up-facing CELL is heavy enough. 3 NECTAR (135 g)
 *    start in it, so TIP #1 needs only ~5 more POLLEN; TIP #2 needs a full
 *    CELL (~11 POLLEN) in the OTHER CELL, which then faces the far wall.
 *  - Our shooter is ~100% accurate from a stationary spot on the "plateau"
 *    found by the simulator's shot maps - but only one ball at a time, and
 *    only once the robot has stopped moving.
 *  - The robot may hold 4 balls (G407), so the AUTO is a loop of
 *    "collect up to 4 -> drive to the right corner -> shoot".
 *
 * THE LOOP (RED shown; BLUE is the same field turned 180 degrees):
 *  1. Which CELL is up? The HIVE's AprilTags tell us (HiveWatcher). If the
 *     tags vanish from our first spot, the HIVE has tipped.
 *  2. If holding balls: shoot them at the up CELL from its corner
 *     (S1 = audience corner for the first CELL, S2 = far corner after a TIP).
 *     Only when the CELL is nearly full do we pause after each shot to see if
 *     it tips - no point wasting balls on a tipping CELL.
 *  3. Otherwise collect from the best remaining source: the GARDEN (4 POLLEN
 *     along the audience wall), the far FLOWER's pocket (4), the wall FLOWER's
 *     pocket (4) or NECTAR the human player put in our LOADING ZONE.
 *  4. Leave enough time to PARK partly in the LOADING ZONE, off the wall.
 *
 * LEGAL (checked by the simulator's rule checker): G304 start, G402 never
 * crosses the center line, G407 max 4 held, G408 only our own NECTAR, G409
 * we never collect under a tipping HIVE, G410 no NECTAR in FLOWERS, and the
 * last launch is early enough that every TIP finishes BEFORE AUTO ends
 * (§10.5: achievements in the transition may be penalized).
 *
 * Every number is "public static": tune it live in the simulator (or with
 * FTC Dashboard). Positions are RED-frame inches.
 */
public abstract class BiobuzzAuto extends LinearOpMode {

    // ---- Start (must match where the drive team puts the robot)
    public static double START_X = -63.5;
    public static double START_Y = -40.0;
    public static double START_H = 0;

    // ---- Shooting spots: the middle of the simulator's 20/20 "plateau" (see README)
    public static double S1_X = -60;
    public static double S1_Y = -56;
    public static double S2_X = -60;
    public static double S2_Y = 56;
    /** Launch angle we ask for (hood mode); gate mode always uses Shooter.FIXED_ANGLE_DEG. */
    public static double LAUNCH_ANGLE = 60;
    public static double RPM_SCALE = 1.02;
    public static double POS_TOL = 1.5;
    public static double HEADING_TOL_DEG = 1.0;
    /** Only shoot when this still (in/s). Shooting while settling cost ~25% accuracy. */
    public static double SHOOT_MAX_SPEED = 1.5;
    /** Wait this long after arriving before the first shot (vision + drive settle). */
    public static double SETTLE_S = 0.15;

    // ---- TIP watching
    /** Mass that TIPS a CELL (grams). ESTIMATE - the real HIVE must be weighed! */
    public static double TIP_MASS_G = 250;
    public static double POLLEN_G = 23;
    public static double NECTAR_G = 45;
    public static double STAGED_NECTAR_G = 135;
    /** Assumed fraction of our shots that go in (for the mass estimate). */
    public static double HIT_RATE = 0.9;
    /** When the next ball could TIP the CELL, watch this long after each shot. */
    public static double TIP_WATCH_S = 0.45;
    /**
     * After the last shot, keep watching this long for the TIP to show on the tags.
     * Our partner's balls often tip it just after our volley; seeing that here
     * (instead of after the next collection) saves ~2.5 s of driving.
     */
    public static double TIP_CONFIRM_S = 1.3;
    /** From S1 before a TIP the tags are always visible; none for this long = it TIPPED. */
    public static double NO_TAGS_MEANS_TIPPED_S = 0.5;
    /** At the far corner, shoot anyway if the tags can't be seen for this long (e.g. a robot blocks them). */
    public static double CONFIRM_TIMEOUT_S = 0.6;

    // ---- GARDEN sweep (balls sit along the audience wall at y ~ -70.6)
    /** Aim a little INTO the audience wall: the wall lines the robot up ("wall ride"). */
    public static double GARDEN_Y = -66;
    /** Where the sweep starts (reachable: the robot's center can't get closer than 8.5 in to the wall). */
    public static double GARDEN_ENTRY_Y = -62.5;
    public static double GARDEN_FROM_X = -44;
    public static double GARDEN_TO_X = -62.5;
    /**
     * The GARDEN balls touch each other (2.8 in apart), so the intake can grab two at once
     * and jam. Measured in the sim over 12 seeds: 18 in/s -> 1.7 jams per match,
     * 14 -> 0.7, 8 -> 0.2. So: fast up to the first ball, then slow through the line.
     */
    public static double GARDEN_SPEED = 8;
    public static double GARDEN_APPROACH_SPEED = 30;
    /**
     * Robot CENTER x (red frame) where the slow part starts. The intake grabs a ball up to
     * ~12 in ahead of the center (8.5 in bumper + 3 in mouth), and the first GARDEN ball is
     * at x = -62.4, so slow down by x = -46 (8 in of margin to brake).
     */
    public static double GARDEN_SLOW_FROM_X = -46;
    /** After a jam is cleared, back off this far so the two balls separate, then creep back in. */
    public static double JAM_BACKOFF_IN = 4;
    public static double RECAPTURE_SPEED = 5;

    // ---- FLOWER pockets (G418 lets us take POLLEN out of the bottom)
    public static double FAR_FLOWER_X = -24;
    public static double FAR_FLOWER_Y = 57.3;
    public static double WALL_FLOWER_X = -57.3;
    public static double WALL_FLOWER_Y = -24;
    public static double FLOWER_S_PER_BALL = 0.6;
    /** Aim this far past the pocket, slowly: the FLOWER's pipes stop us exactly at the pocket. */
    public static double FLOWER_NUDGE = 2.0;

    // ---- LOADING ZONE NECTAR (the human player puts it against the red wall, y 24..48)
    public static boolean USE_ZONE_NECTAR = true;
    /**
     * The human player's NECTAR rolls 2-6 in off the wall (x -70 to -66 in the sim). The
     * intake takes balls 7.6-12.4 in ahead of the robot's center, so sweep at x = -56.5
     * (window -68.9..-64.1; slower than the wall-hugging ones gets pushed, not taken).
     * Start at y = 34: our partner parks in the low end of the zone.
     */
    public static double ZONE_SWEEP_X = -56.5;
    public static double ZONE_SWEEP_Y_FROM = 34;
    public static double ZONE_SWEEP_Y_TO = 47;
    public static double ZONE_SWEEP_SPEED = 20;

    // ---- End of AUTO
    public static double PARK_X = -61.0;
    public static double PARK_Y = 42;
    public static double PARK_SPEED = 70;
    /** Last moment to LAUNCH: ball flight ~0.4 s + TIP swing 0.8 s must end before 30 s. */
    public static double LAST_LAUNCH_S = 28.5;
    /** Must be parked by then (AUTO ends at 30 s). */
    public static double PARKED_BY_S = 29.7;
    /** Average speed when estimating how long the drive to PARK takes (in/s). */
    public static double PARK_EST_SPEED = 45;
    /** Clear lane (red frame) between the audience half and the far half. */
    public static double LANE_X = -42;

    protected abstract boolean isBlue();

    private RobotHardware robot;
    private Shooter shooter;
    private Intake intake;
    private Localizer loc;
    private DriveController drive;
    private HiveWatcher hive;
    private final ElapsedTime match = new ElapsedTime();
    private boolean blue;
    private String phase = "init";

    // What we know / believe
    private int tipsSeen;
    private boolean secondCellUp;
    private double cellMassEstimate;  // grams in the up CELL from us (partners may add more)
    private int held = 4;             // balls we think we hold (no sensor: counted)
    /**
     * What we think is in the robot, in order (true = NECTAR). Storage is single-file,
     * first in -> first out, and we know what each source gives, so we know which ball
     * comes out next and can pick its RPM - no color sensor needed.
     */
    private final java.util.ArrayDeque<Boolean> queue = new java.util.ArrayDeque<>();
    private boolean gardenDone;
    private boolean farFlowerDone;
    private boolean wallFlowerDone;
    private int zoneSweeps;
    private boolean onFarSide;

    // =====================================================================

    @Override
    public void runOpMode() {
        blue = isBlue();
        robot = new RobotHardware();
        robot.init(hardwareMap);
        shooter = new Shooter(robot);
        intake = new Intake(robot);
        loc = new Localizer(robot);
        drive = new DriveController(new MecanumDrive(robot), loc);
        hive = new HiveWatcher(blue);
        robot.limelight.pipelineSwitch(0); // AprilTags
        robot.limelight.start();

        telemetry.addData("AUTO", "%s, start %s", blue ? "BLUE" : "RED", red(START_X, START_Y, START_H));
        telemetry.addData("Shooter", Shooter.HOOD_MODE ? "hood" : "gate");
        telemetry.update();
        waitForStart();
        match.reset();
        Pose start = at(START_X, START_Y, START_H);
        loc.setPose(start.x, start.y, start.heading);
        cellMassEstimate = STAGED_NECTAR_G;
        for (int i = 0; i < 4; i++) {
            queue.add(false); // 4 pre-loaded POLLEN
        }

        while (opModeIsActive() && !mustPark()) {
            if (held > 0) {
                shootHeld();
            } else if (!collectSomething()) {
                break; // nothing left worth getting in the time we have
            }
        }

        phase = "park";
        park();
        while (opModeIsActive()) {
            update();
            drive.stop();
        }
    }

    // =====================================================================
    // The plan
    // =====================================================================

    /** Shoots what we hold at whichever CELL is up. */
    private void shootHeld() {
        if (!secondCellUp) {
            phase = "shoot CELL 1";
            shootAt(S1_X, S1_Y, FieldConstants.AUDIENCE_CELL_OPENING);
        } else {
            phase = "shoot CELL 2";
            if (!onFarSide) {
                viaLane(true);
            }
            shootAt(S2_X, S2_Y, FieldConstants.FAR_CELL_OPENING);
        }
    }

    /**
     * Picks the next source of balls: first what's near the corner we shoot
     * from next, then the rest. Returns false if nothing fits in the time left.
     */
    private boolean collectSomething() {
        double left = LAST_LAUNCH_S - match.seconds();
        if (!secondCellUp) {
            // Still working on TIP #1: everything near the audience corner.
            if (!gardenDone && left > 5) {
                phase = "GARDEN";
                sweepGarden();
                return true;
            }
            if (!wallFlowerDone && left > 6) {
                phase = "wall FLOWER";
                pullFromFlower(at(WALL_FLOWER_X, WALL_FLOWER_Y, 180));
                wallFlowerDone = true;
                return true;
            }
            return false;
        }
        // TIP #2: the far CELL. GARDEN first if it's still there and we're on the audience side.
        if (!gardenDone && !onFarSide && left > 8) {
            phase = "GARDEN";
            sweepGarden();
            return true;
        }
        if (!farFlowerDone && left > 5) {
            phase = "far FLOWER";
            if (!onFarSide) {
                viaLane(true);
            }
            pullFromFlower(at(FAR_FLOWER_X, FAR_FLOWER_Y, 90));
            farFlowerDone = true;
            return true;
        }
        if (USE_ZONE_NECTAR && zoneSweeps < 2 && left > 4) {
            phase = "zone NECTAR";
            if (!onFarSide) {
                viaLane(true);
            }
            sweepLoadingZone();
            zoneSweeps++;
            return true;
        }
        if (!wallFlowerDone && left > 7) {
            phase = "wall FLOWER";
            pullFromFlower(at(WALL_FLOWER_X, WALL_FLOWER_Y, 180));
            wallFlowerDone = true;
            return true;
        }
        return false;
    }

    /** True when it's time to head for the PARK spot. */
    private boolean mustPark() {
        Pose p = at(PARK_X, PARK_Y, 90);
        double d = Math.hypot(p.x - loc.getX(), p.y - loc.getY()) + (onFarSide ? 0 : 30);
        return match.seconds() + d / PARK_EST_SPEED + 0.8 > PARKED_BY_S;
    }

    // =====================================================================
    // Building blocks
    // =====================================================================

    /** Everything that must run every loop: odometry, camera, TIP watching, telemetry. */
    private void update() {
        loc.update();
        LLResult r = robot.limelight.getLatestResult();
        loc.addVision(r);
        hive.update(r);
        onFarSide = redY(loc.getY()) > 0;
        telemetry.addData("t", "%.1f  %s", match.seconds(), phase);
        telemetry.addData("pose", "%.1f, %.1f, %.0f deg", loc.getX(), loc.getY(), Math.toDegrees(loc.getHeading()));
        telemetry.addData("hive", "%s  tips seen %d  CELL %s ~%.0f g", hive.getState(), tipsSeen,
                secondCellUp ? "2" : "1", cellMassEstimate);
        telemetry.addData("held", held);
        telemetry.addData("flywheel", "%.0f / %.0f rpm, shots %d", shooter.getRpm(), shooter.getTargetRpm(),
                shooter.getShotsDetected());
        telemetry.update();
    }

    /** Converts RED-frame numbers to our alliance's field frame. */
    private Pose at(double x, double y, double headingDeg) {
        return FieldConstants.forAlliance(Pose.deg(x, y, headingDeg), blue);
    }

    /** Field y -> red-frame y. */
    private double redY(double y) {
        return blue ? -y : y;
    }

    private static String red(double x, double y, double h) {
        return String.format("(%.1f, %.1f, %.0f)", x, y, h);
    }

    private boolean timeLeft(double seconds) {
        return match.seconds() + seconds < LAST_LAUNCH_S;
    }

    /** A TIP just happened (or we found out it had). */
    private void onTip() {
        tipsSeen++;
        secondCellUp = !secondCellUp;
        cellMassEstimate = 0;
    }

    /** Drives to a pose. Returns false if it ran out of time. */
    private boolean driveTo(Pose p, double maxSpeed, double tol, double timeout) {
        ElapsedTime t = new ElapsedTime();
        while (opModeIsActive() && t.seconds() < timeout) {
            update();
            double d = drive.step(p.x, p.y, p.heading, maxSpeed);
            if (d < tol && Math.abs(drive.headingError(p.heading)) < Math.toRadians(HEADING_TOL_DEG * 2)
                    && loc.getSpeed() < 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drives to a shooting spot facing the CELL opening and shoots what we hold,
     * one ball at a time. Watches the HIVE's tags and stops when it TIPS.
     */
    private void shootAt(double sx, double sy, double[] redOpening) {
        double[] o = FieldConstants.forAlliance(redOpening, blue);
        Pose spot = at(sx, sy, 0);
        double aim = Math.atan2(o[1] - spot.y, o[0] - spot.x);
        Pose p = new Pose(spot.x, spot.y, aim);
        double angle = shooter.setLaunchAngle(LAUNCH_ANGLE);
        double dist = Math.hypot(o[0] - spot.x, o[1] - spot.y) - FieldConstants.SHOT_EXIT_FORWARD;
        double rise = o[2] - FieldConstants.SHOT_EXIT_HEIGHT;
        double rpmPollen = ShotSolver.rpm(dist, rise, angle, false) * RPM_SCALE;
        double rpmNectar = ShotSolver.rpm(dist, rise, angle, true) * RPM_SCALE;
        shooter.setTargetRpm(nextIsNectar() ? rpmNectar : rpmPollen);
        driveTo(p, DriveController.MAX_SPEED_IN_S, POS_TOL, 4.0);

        ElapsedTime here = new ElapsedTime();
        ElapsedTime noTags = new ElapsedTime();
        shooter.resetEmptyFeeds();
        ElapsedTime sinceShot = new ElapsedTime();
        boolean watching = false;
        int shotsHere = 0;
        while (opModeIsActive() && held > 0 && timeLeft(0) && shooter.getEmptyFeeds() == 0) {
            update();
            drive.step(p.x, p.y, p.heading, 20); // hold position and aim
            HiveWatcher.State st = hive.getState();
            if (st != HiveWatcher.State.UNKNOWN) {
                noTags.reset();
            }
            if (tipDetected(st)) {
                onTip();
                return; // the other CELL is up now: the plan loop decides what's next
            }
            // Before any TIP, the first CELL's tags are always visible from S1. None = it tipped.
            if (!secondCellUp && noTags.seconds() > NO_TAGS_MEANS_TIPPED_S && here.seconds() > NO_TAGS_MEANS_TIPPED_S) {
                onTip();
                return;
            }
            if (watching && sinceShot.seconds() < TIP_WATCH_S) {
                continue;
            }
            watching = false;
            boolean settled = here.seconds() > SETTLE_S
                    && Math.abs(drive.headingError(p.heading)) < Math.toRadians(HEADING_TOL_DEG)
                    && Math.hypot(p.x - loc.getX(), p.y - loc.getY()) < POS_TOL * 2
                    && loc.getSpeed() < SHOOT_MAX_SPEED && Math.abs(Math.toDegrees(loc.getOmega())) < 10;
            // Never feed a CELL we haven't SEEN is up: the first CELL must show its tags
            // (if they're gone, the rule above decides it TIPPED). At the far corner the
            // tags can be blocked by a robot, so there we shoot anyway after a moment.
            boolean confirmed = secondCellUp ? (st == HiveWatcher.State.SECOND_UP || here.seconds() > CONFIRM_TIMEOUT_S)
                    : st == HiveWatcher.State.FIRST_UP;
            if ((!settled || !confirmed) && !shooter.isGateOpen()) {
                continue;
            }
            shooter.setTargetRpm(nextIsNectar() ? rpmNectar : rpmPollen);
            if (shooter.fireSingle()) {
                boolean wasNectar = !queue.isEmpty() && queue.poll();
                held--;
                shotsHere++;
                cellMassEstimate += (wasNectar ? NECTAR_G : POLLEN_G) * HIT_RATE;
                sinceShot.reset();
                // Could the NEXT ball tip it? Then watch before firing again.
                watching = cellMassEstimate + POLLEN_G >= TIP_MASS_G - POLLEN_G;
            }
        }
        if (shooter.isGateOpen()) {
            shooter.closeGate();
        }
        if (shooter.getEmptyFeeds() > 0) {
            held = 0; // we were wrong about what we held
            queue.clear();
        }
        // The last ball's TIP may show on the tags a moment later.
        if (shotsHere > 0 && cellMassEstimate >= TIP_MASS_G - 2 * POLLEN_G) {
            ElapsedTime watch = new ElapsedTime();
            while (opModeIsActive() && watch.seconds() < TIP_CONFIRM_S) {
                update();
                drive.step(p.x, p.y, p.heading, 20);
                if (tipDetected(hive.getState())
                        || !secondCellUp && hive.secondsSinceSeen() > NO_TAGS_MEANS_TIPPED_S) {
                    onTip();
                    return;
                }
            }
        }
    }

    private boolean nextIsNectar() {
        return !queue.isEmpty() && queue.peek();
    }

    /** Records n more balls of one kind at the back of the queue (capped at 4). */
    private void loaded(int n, boolean nectar) {
        for (int i = 0; i < n && queue.size() < 4; i++) {
            queue.add(nectar);
        }
        held = queue.size();
    }

    private boolean tipDetected(HiveWatcher.State st) {
        if (st == HiveWatcher.State.TIPPING) {
            return true;
        }
        return secondCellUp ? st == HiveWatcher.State.FIRST_UP : st == HiveWatcher.State.SECOND_UP;
    }

    /** Sweeps the 4 GARDEN POLLEN along the audience wall into the intake. */
    private void sweepGarden() {
        Pose entry = at(GARDEN_FROM_X, GARDEN_ENTRY_Y, 180);
        Pose end = at(GARDEN_TO_X, GARDEN_Y, 180);
        driveTo(entry, DriveController.MAX_SPEED_IN_S, 2.0, 3.0);
        // Wall ride: the target is slightly INSIDE the wall, so we keep pressing into it
        // while sliding along; we stop on the x coordinate only.
        ElapsedTime sweep = new ElapsedTime();
        double slowFrom = blue ? -GARDEN_SLOW_FROM_X : GARDEN_SLOW_FROM_X;
        while (opModeIsActive() && sweep.seconds() < 3.5) {
            update();
            intake.intake();
            boolean beforeFirstBall = blue ? loc.getX() < slowFrom : loc.getX() > slowFrom;
            drive.step(end.x, end.y, end.heading, beforeFirstBall ? GARDEN_APPROACH_SPEED : GARDEN_SPEED);
            if (Math.abs(loc.getX() - end.x) < 1.0) {
                break;
            }
        }
        settleIntake(end);
        gardenDone = true;
        loaded(4, false);
    }

    /**
     * Pulls POLLEN out of a FLOWER's bottom pocket, one at a time (G418 allows
     * the retrieval opening). We creep a little past the pocket so the FLOWER's
     * pipes stop the robot exactly where the intake meets it.
     */
    private void pullFromFlower(Pose station) {
        driveTo(station, DriveController.MAX_SPEED_IN_S, 2.0, 3.5);
        Pose nudge = new Pose(station.x + Math.cos(station.heading) * FLOWER_NUDGE,
                station.y + Math.sin(station.heading) * FLOWER_NUDGE, station.heading);
        int want = 4 - held;
        ElapsedTime t = new ElapsedTime();
        while (opModeIsActive() && t.seconds() < want * FLOWER_S_PER_BALL && timeLeft(2.0)) {
            update();
            intake.intake();
            drive.step(nudge.x, nudge.y, nudge.heading, 8);
        }
        settleIntake(null);
        loaded(want, false);
    }

    /**
     * Strafes along the red wall inside the LOADING ZONE with the intake facing
     * the wall: the human player drops NECTAR against the wall here (45 g -
     * twice a POLLEN, great for TIPS). Only our own NECTAR (G408).
     */
    private void sweepLoadingZone() {
        Pose a = at(ZONE_SWEEP_X, ZONE_SWEEP_Y_FROM, 180);
        Pose b = at(ZONE_SWEEP_X, ZONE_SWEEP_Y_TO, 180);
        driveTo(a, DriveController.MAX_SPEED_IN_S, 2.0, 3.0);
        ElapsedTime t = new ElapsedTime();
        while (opModeIsActive() && t.seconds() < 2.0) {
            update();
            intake.intake();
            if (drive.step(b.x, b.y, b.heading, ZONE_SWEEP_SPEED) < 1.0) {
                break;
            }
        }
        settleIntake(null);
        loaded(1, true); // usually one NECTAR (G426: one per TIP); if none, the first feed times out
    }

    /** Minimum intake run after collecting: long enough for Intake to notice a jam (2 checks). */
    public static double SETTLE_INTAKE_S = 0.45;
    /** After a jam is cleared, keep intaking this long to take the spat-out balls back in. */
    public static double RECAPTURE_S = 0.5;

    /**
     * Keeps the intake running so the last ball transfers. If two balls jammed,
     * Intake reverses to clear it (spitting them out); then we back off so they
     * separate and creep back in toward {@code creepTo} to take them one by one.
     */
    private void settleIntake(Pose creepTo) {
        ElapsedTime settle = new ElapsedTime();
        while (opModeIsActive() && settle.seconds() < SETTLE_INTAKE_S) {
            update();
            intake.intake();
            drive.stop();
            if (intake.isUnjamming()) {
                recoverFromJam(creepTo);
                break;
            }
        }
        intake.stop();
    }

    private void recoverFromJam(Pose creepTo) {
        double h = loc.getHeading();
        Pose back = new Pose(loc.getX() - Math.cos(h) * JAM_BACKOFF_IN, loc.getY() - Math.sin(h) * JAM_BACKOFF_IN, h);
        Pose in = creepTo != null ? creepTo : new Pose(loc.getX(), loc.getY(), h);
        ElapsedTime t = new ElapsedTime();
        // 1) let Intake finish reversing while we back off
        while (opModeIsActive() && t.seconds() < 0.6) {
            update();
            intake.intake(); // still reversing inside Intake while unjamming
            drive.step(back.x, back.y, back.heading, 15);
        }
        // 2) creep back in slowly with the intake running
        t.reset();
        while (opModeIsActive() && t.seconds() < RECAPTURE_S + 0.6 && timeLeft(2.0)) {
            update();
            intake.intake();
            drive.step(in.x, in.y, in.heading, RECAPTURE_SPEED);
        }
    }

    /**
     * Crosses between the audience half and the far half through a clear lane
     * (x = LANE_X, red frame): straight lines would clip our partner or the HIVE
     * legs, and a robot pushing an obstacle ruins odometry.
     */
    private void viaLane(boolean toFar) {
        double y0 = toFar ? -10 : 10;
        double y1 = toFar ? 10 : -10;
        passThrough(at(LANE_X, y0, toFar ? 90 : -90));
        passThrough(at(LANE_X, y1, toFar ? 90 : -90));
    }

    /** Drives through a waypoint without stopping (loose tolerance). */
    private void passThrough(Pose p) {
        ElapsedTime t = new ElapsedTime();
        while (opModeIsActive() && t.seconds() < 3.0) {
            update();
            if (drive.stepThrough(p.x, p.y, p.heading, DriveController.MAX_SPEED_IN_S * 0.85) < 8) {
                return;
            }
        }
    }

    /** Ends partly in the LOADING ZONE and not touching the wall (PARK 5 + keeps LEAVE 3). */
    private void park() {
        shooter.setTargetRpm(0);
        intake.stop();
        if (!onFarSide) {
            viaLane(true);
        }
        driveTo(at(PARK_X, PARK_Y, 90), PARK_SPEED, 1.5, 4.0);
        drive.stop();
    }
}

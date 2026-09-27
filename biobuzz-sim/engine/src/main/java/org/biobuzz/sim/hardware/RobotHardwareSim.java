package org.biobuzz.sim.hardware;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.game.GameWorld;
import org.biobuzz.sim.physics.MotorSpec;
import org.biobuzz.sim.physics.MotorState;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.SimRandom;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * Builds our robot's simulated hardware from config/hubs.jsonc - the same
 * job the Driver Station's robot configuration does on the real robot.
 *
 * It also checks the configuration against the game rules (8 motors, 8
 * servos, ports per hub) and against the names in RobotHardware.java.
 */
public final class RobotHardwareSim {

    public final HardwareMap hardwareMap = new HardwareMap();
    public final List<SimHub> hubs = new ArrayList<>();
    /** Motor physics by role, e.g. "drive.frontLeft". */
    public final Map<String, MotorState> motorsByRole = new LinkedHashMap<>();
    /** Motor ports by role. */
    public final Map<String, SimDcMotor> motorPortsByRole = new LinkedHashMap<>();
    /** Physical mirroring by role: -1 if mountedReversed. */
    public final Map<String, Double> mountSignByRole = new HashMap<>();
    public final Map<String, SimServo> servosByRole = new LinkedHashMap<>();
    public SimImu imu;
    public SimLimelight limelight;
    public SimDistanceSensor possessionSensor;
    /** Problems found while building (shown in the browser and printed at startup). */
    public final List<String> warnings = new ArrayList<>();

    public RobotHardwareSim(SimConfig cfg, SimRobot robot, DoubleSupplier batteryVoltage, SimRandom random,
                            GameWorld world, java.util.function.Supplier<List<SimRobot>> allRobots) {
        HubTiming timing = new HubTiming(cfg.hubs.obj("timingMs"));
        Map<String, MotorSpec> specs = new HashMap<>();
        Cfg models = cfg.robot.obj("motorModels");
        for (String id : models.keys()) {
            specs.put(id, new MotorSpec(id, models.obj(id)));
        }

        Set<String> allNames = new LinkedHashSet<>();
        int motorCount = 0;
        int servoCount = 0;

        for (Cfg h : cfg.hubs.objList("hubs")) {
            boolean control = h.str("type").equals("ControlHub");
            SimHub hub = new SimHub(h.str("name"), control, timing, batteryVoltage);
            hubs.add(hub);
            hardwareMap.dcMotorController.put(hub.name, hub);
            hardwareMap.servoController.putLocal(hub.name, hub);
            hardwareMap.voltageSensor.putLocal(hub.name, hub);
            // (dcMotorController.put above also registers the hub by name, so
            //  hardwareMap.getAll(LynxModule.class) finds it for bulk caching.)

            for (Cfg m : h.objList("motors")) {
                int port = m.integer("port");
                String name = m.str("name");
                if (port < 0 || port >= SimHub.MOTOR_PORTS) {
                    warnings.add(String.format("%s: motor \"%s\" is on port %d, but hubs only have ports 0-%d.",
                            hub.name, name, port, SimHub.MOTOR_PORTS - 1));
                    continue;
                }
                if (hub.motors[port] != null) {
                    warnings.add(String.format("%s: two motors on port %d (\"%s\" and \"%s\"). "
                                    + "R505 allows 2 motors per port only with a Y-cable, and then only one encoder works.",
                            hub.name, port, hub.motors[port].configName(), name));
                    continue;
                }
                MotorSpec spec = specs.get(m.str("model"));
                if (spec == null) {
                    throw new IllegalArgumentException("hubs.jsonc: unknown motor model \"" + m.str("model")
                            + "\" for \"" + name + "\" (see robot.jsonc motorModels)");
                }
                String role = m.str("role");
                MotorState state = new MotorState(spec, role);
                SimDcMotor motor = new SimDcMotor(name, hub, port, state, timing);
                hardwareMap.dcMotor.put(name, motor);
                motorsByRole.put(role, state);
                motorPortsByRole.put(role, motor);
                mountSignByRole.put(role, m.bool("mountedReversed") ? -1.0 : 1.0);
                noteName(allNames, name);
                motorCount++;
            }
            for (Cfg s : h.objList("servos")) {
                int port = s.integer("port");
                SimServo servo = new SimServo(s.str("name"), hub, port, timing.servoWriteNs);
                hardwareMap.servo.put(s.str("name"), servo);
                servosByRole.put(s.str("role"), servo);
                noteName(allNames, s.str("name"));
                servoCount++;
            }
            for (Cfg d : h.objList("i2c")) {
                if (d.bool("optional", false) && d.str("role").equals("possession")
                        && !cfg.robot.bool("possessionSensor.enabled")) {
                    continue; // optional device that robot.jsonc says isn't on the robot
                }
                if (d.str("type").equals("DistanceSensor")) {
                    possessionSensor = new SimDistanceSensor(d.str("name"), timing.imuReadNs, random.stream("possession"));
                    hardwareMap.put(d.str("name"), possessionSensor);
                    noteName(allNames, d.str("name"));
                }
                if (d.str("type").equals("IMU")) {
                    Cfg imuCfg = cfg.robot.obj("imu");
                    imu = new SimImu(d.str("name"), robot, timing.imuReadNs,
                            imuCfg.str("logoFacing"), imuCfg.str("usbFacing"), warnings::add,
                            random.stream("imu"), imuCfg.num("yawNoiseDeg"), imuCfg.num("rateNoiseDegPerSec"),
                            imuCfg.num("driftSigmaDegPerMin"));
                    hardwareMap.put(d.str("name"), imu);
                    noteName(allNames, d.str("name"));
                }
            }
        }
        // USB devices: the Limelight 3A.
        for (Cfg u : cfg.hubs.objList("usb")) {
            noteName(allNames, u.str("name"));
            if (u.str("type").equals("Limelight3A")) {
                limelight = new SimLimelight(u.str("name"), robot, allRobots, world, cfg.robot.obj("camera"),
                        cfg.game.obj("aprilTags"), random.stream("limelight"));
                hardwareMap.put(u.str("name"), limelight);
            }
        }

        checkRules(cfg.game, motorCount, servoCount);
        checkNamesAgainstRobotHardware(allNames);
    }

    private void noteName(Set<String> names, String name) {
        if (!names.add(name)) {
            warnings.add("Two devices are both named \"" + name + "\" - hardwareMap.get() may return the wrong one.");
        }
    }

    private void checkRules(Cfg game, int motors, int servos) {
        int maxMotors = game.integer("robotRules.maxMotors");
        int maxServos = game.integer("robotRules.maxServos");
        if (motors > maxMotors) {
            warnings.add(String.format("RULE R503: %d motors configured, the limit is %d.", motors, maxMotors));
        }
        if (servos > maxServos) {
            warnings.add(String.format("RULE R503: %d servos configured, the limit is %d.", servos, maxServos));
        }
    }

    /**
     * Compares the names in hubs.jsonc with the String constants in
     * TeamCode's RobotHardware class. A constant that isn't in the config is
     * almost certainly a typo - the robot would crash at init.
     */
    private void checkNamesAgainstRobotHardware(Set<String> configNames) {
        Class<?> rh;
        try {
            rh = Class.forName("org.firstinspires.ftc.teamcode.RobotHardware");
        } catch (ClassNotFoundException e) {
            return; // no RobotHardware class: nothing to compare
        }
        for (Field f : rh.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || f.getType() != String.class) {
                continue;
            }
            try {
                f.setAccessible(true);
                String value = (String) f.get(null);
                if (!configNames.contains(value)) {
                    warnings.add(String.format("RobotHardware.%s = \"%s\" is not in hubs.jsonc%s. "
                                    + "hardwareMap.get() will fail for it, exactly like on the robot.",
                            f.getName(), value, suggestion(value, configNames)));
                }
            } catch (IllegalAccessException e) {
                // ignore fields we can't read
            }
        }
    }

    private static String suggestion(String value, Set<String> names) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String n : names) {
            int d = editDistance(value.toLowerCase(), n.toLowerCase());
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best != null && bestDist <= 3 ? " (did you mean \"" + best + "\"?)" : "";
    }

    /** Levenshtein distance: how many single-letter edits turn a into b. */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Resets every device to its defaults, like the SDK does before each OpMode. */
    public void resetForNewOpMode() {
        for (SimHub h : hubs) {
            h.resetDeviceConfigurationForOpMode();
        }
        for (SimDcMotor m : motorPortsByRole.values()) {
            m.resetDeviceConfigurationForOpMode();
            m.setPower(0.0);
        }
        for (SimServo s : servosByRole.values()) {
            s.resetDeviceConfigurationForOpMode();
        }
        if (limelight != null) {
            limelight.resetDeviceConfigurationForOpMode();
        }
    }

    /** Every motor's physics state (for the battery). */
    public List<MotorState> allMotors() {
        return new ArrayList<>(motorsByRole.values());
    }

    /** Runs each hub's built-in motor control and the IMU drift for one physics step. */
    public void step(double dt) {
        for (SimDcMotor m : motorPortsByRole.values()) {
            m.hubStep(dt);
        }
        if (imu != null) {
            imu.step(dt);
        }
    }

    /** Stops every motor (the SDK does this when an OpMode ends). */
    public void stopAllMotors() {
        for (SimDcMotor m : motorPortsByRole.values()) {
            m.stopForOpModeEnd();
        }
    }
}

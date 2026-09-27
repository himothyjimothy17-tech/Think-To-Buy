package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;
import com.qualcomm.robotcore.util.ElapsedTime;

/**
 * Watches our HIVE with the Limelight to tell which CELL is up, and when it TIPS.
 *
 * HOW: each CELL has 4 AprilTags on its bottom face (§9.9). The DOWN cell's
 * tags hang at a steady low height (~35 in); the UP cell's tags are much
 * higher (or out of view). So: if we see the tags of CELL A low and steady,
 * CELL B is up. When those low tags start rising, the HIVE is TIPPING.
 *
 * "first" CELL = the one that starts up (red: audience end; blue: far end,
 * the 180-degree rotation). Tag IDs from §9.9 / Fig 9-17.
 */
public class HiveWatcher {

    public enum State { FIRST_UP, SECOND_UP, TIPPING, UNKNOWN }

    /** Tags at or below this height (in, robot frame) are a DOWN cell at rest. ESTIMATE from the drawings. */
    public static double DOWN_HEIGHT_MAX = 36.5;
    public static double UP_HEIGHT_MIN = 44.0;

    private final int[] firstIds;
    private final int[] secondIds;
    private State state = State.UNKNOWN;
    private final ElapsedTime sinceSeen = new ElapsedTime();

    public HiveWatcher(boolean blue) {
        firstIds = blue ? new int[] {42, 43, 44, 45} : new int[] {34, 35, 36, 37};
        secondIds = blue ? new int[] {38, 39, 40, 41} : new int[] {30, 31, 32, 33};
    }

    /** Feed it every AprilTag result. */
    public void update(LLResult r) {
        if (r == null || !r.isValid()) {
            return;
        }
        double firstZ = Double.NaN;
        double secondZ = Double.NaN;
        for (LLResultTypes.FiducialResult f : r.getFiducialResults()) {
            if (f.getTargetPoseRobotSpace() == null) {
                continue;
            }
            double z = f.getTargetPoseRobotSpace().getPosition().z / 0.0254;
            if (contains(firstIds, f.getFiducialId())) {
                firstZ = z;
            } else if (contains(secondIds, f.getFiducialId())) {
                secondZ = z;
            }
        }
        State s = State.UNKNOWN;
        if (!Double.isNaN(secondZ)) {
            s = secondZ <= DOWN_HEIGHT_MAX ? State.FIRST_UP : (secondZ < UP_HEIGHT_MIN ? State.TIPPING : State.SECOND_UP);
        } else if (!Double.isNaN(firstZ)) {
            s = firstZ <= DOWN_HEIGHT_MAX ? State.SECOND_UP : (firstZ < UP_HEIGHT_MIN ? State.TIPPING : State.FIRST_UP);
        }
        if (s != State.UNKNOWN) {
            state = s;
            sinceSeen.reset();
        }
    }

    /** How long a sighting stays valid. Short: a TIP can make our tags vanish in a moment. */
    public static double STALE_S = 0.3;

    /** Last state we saw (UNKNOWN if nothing seen for STALE_S). */
    public State getState() {
        return sinceSeen.seconds() > STALE_S ? State.UNKNOWN : state;
    }

    /** Seconds since we last saw any of our HIVE's tags. */
    public double secondsSinceSeen() {
        return sinceSeen.seconds();
    }

    private static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }
}

package org.biobuzz.sim.field;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.geom.Circle;
import org.biobuzz.sim.geom.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * The BIOBUZZ field: walls, zones, FLOWERS and the HIVE frame.
 *
 * Every number comes from config/game.jsonc (which cites the manual).
 * Only RED positions are written in the config; BLUE is built here by
 * rotating 180 degrees around the field center, so both sides always match.
 *
 * All values are in METERS.
 */
public final class Field {

    /** Half the field width: walls are at x = +/-half and y = +/-half. */
    public final double half;

    public final Map<Alliance, Rect> loadingZone = new EnumMap<>(Alliance.class);
    public final Map<Alliance, Rect> garden = new EnumMap<>(Alliance.class);
    public final Map<Alliance, Rect> allianceArea = new EnumMap<>(Alliance.class);

    /** The four FLOWERS (centers), for scoring and drawing. */
    public final List<Circle> flowers;

    /** Everything round a robot can bump into (FLOWERS and HIVE legs). */
    public final List<Circle> obstacles;

    public Field(Cfg game, double robotHeightM) {
        half = inToM(game.num("field.size")) / 2.0;

        for (Alliance a : Alliance.values()) {
            loadingZone.put(a, zone(game.obj("zones.red.loadingZone"), a));
            garden.put(a, zone(game.obj("zones.red.garden"), a));
            allianceArea.put(a, zone(game.obj("zones.red.allianceArea"), a));
        }

        List<Circle> flowerList = new ArrayList<>();
        double flowerOffset = inToM(game.num("flower.centerFromWall"));
        double flowerRadius = inToM(game.num("flower.collisionRadius"));
        for (Cfg f : game.objList("flower.redSidePositions")) {
            // Move the center off the wall by centerFromWall (toward the field center).
            double x = inToM(f.num("x"));
            double y = inToM(f.num("y"));
            if (Math.abs(Math.abs(x) - half) < 1e-6) {
                x -= Math.signum(x) * flowerOffset;
            }
            if (Math.abs(Math.abs(y) - half) < 1e-6) {
                y -= Math.signum(y) * flowerOffset;
            }
            flowerList.add(new Circle(x, y, flowerRadius, "FLOWER (" + f.str("wall") + " wall, red side)"));
            flowerList.add(new Circle(-x, -y, flowerRadius, "FLOWER (" + f.str("wall") + " wall, blue side)"));
        }
        flowers = Collections.unmodifiableList(flowerList);

        List<Circle> obs = new ArrayList<>(flowerList);
        obs.addAll(hiveLegs(game.obj("hive"), robotHeightM));
        obstacles = Collections.unmodifiableList(obs);
    }

    /**
     * The HIVE frame's four legs. Each leg slants from a floor corner up to
     * the apex, so only the lower part matters for driving robots. We model
     * the part of each leg below the top of our robot as a row of small circles.
     * (The frame's base bars sit under the tiles, per Figure 9-7, so robots
     * drive over them.)
     */
    private static List<Circle> hiveLegs(Cfg hive, double robotHeightM) {
        List<Circle> legs = new ArrayList<>();
        double halfWidth = inToM(hive.num("frameWidth")) / 2.0;   // along x
        double halfDepth = inToM(hive.num("frameDepth")) / 2.0;   // along y
        double apexZ = inToM(hive.num("pivotHeight"));
        double legR = inToM(hive.num("legRadius"));
        int samples = 5;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int i = 0; i <= samples; i++) {
                    double z = robotHeightM * i / samples;
                    // Leg's distance from the center line shrinks linearly with height.
                    double y = sy * halfDepth * (1.0 - z / apexZ);
                    legs.add(new Circle(sx * halfWidth, y, legR, "HIVE frame leg"));
                }
            }
        }
        return legs;
    }

    private static Rect zone(Cfg z, Alliance a) {
        Rect red = new Rect(inToM(z.num("xMin")), inToM(z.num("xMax")), inToM(z.num("yMin")), inToM(z.num("yMax")));
        return a == Alliance.RED ? red : red.rotate180();
    }
}

package org.biobuzz.sim.physics;

import org.biobuzz.sim.field.Field;
import org.biobuzz.sim.geom.Circle;
import org.biobuzz.sim.robot.SimRobot;

import java.util.List;

/**
 * Keeps robots out of walls, FLOWERS, HIVE legs and each other.
 *
 * METHOD: after each physics step we look for overlaps and push the robot back
 * out along the shortest direction ("penetration resolution"), then remove the
 * part of its velocity that points into the obstacle. This is simple, stable,
 * and good enough for driving practice. (Stage 2 adds friction when robots
 * push against things; stage 6 lets robots push each other.)
 */
public final class Collisions {

    private Collisions() {
    }

    /** Moves {@code robot} out of anything it overlaps. Returns true if it hit something. */
    public static boolean resolve(SimRobot robot, Field field, List<SimRobot> others) {
        boolean hit = resolveWalls(robot, field.half);
        for (Circle c : field.obstacles) {
            hit |= resolveCircle(robot, c);
        }
        for (SimRobot other : others) {
            if (other != robot) {
                hit |= resolveRobot(robot, other);
            }
        }
        // Walls win: pushing off a FLOWER must never push us through a wall.
        hit |= resolveWalls(robot, field.half);
        return hit;
    }

    static boolean resolveWalls(SimRobot r, double half) {
        double[][] cs = r.corners();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] c : cs) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
        }
        boolean hit = false;
        if (maxX > half) {
            r.x -= maxX - half;
            r.vx = Math.min(r.vx, 0);
            hit = true;
        }
        if (minX < -half) {
            r.x += -half - minX;
            r.vx = Math.max(r.vx, 0);
            hit = true;
        }
        if (maxY > half) {
            r.y -= maxY - half;
            r.vy = Math.min(r.vy, 0);
            hit = true;
        }
        if (minY < -half) {
            r.y += -half - minY;
            r.vy = Math.max(r.vy, 0);
            hit = true;
        }
        return hit;
    }

    /** Robot (a rotated rectangle) against a circle. */
    static boolean resolveCircle(SimRobot r, Circle circle) {
        double c = Math.cos(r.heading);
        double s = Math.sin(r.heading);
        // Circle center in the robot's frame.
        double dx = circle.x - r.x;
        double dy = circle.y - r.y;
        double lx = dx * c + dy * s;
        double ly = -dx * s + dy * c;
        double hl = r.length / 2;
        double hw = r.width / 2;
        // Closest point of the rectangle to the circle center.
        double px = clamp(lx, -hl, hl);
        double py = clamp(ly, -hw, hw);
        double ex = lx - px;
        double ey = ly - py;
        double dist = Math.hypot(ex, ey);
        if (dist >= circle.r) {
            return false;
        }
        double nx;
        double ny;
        double push;
        if (dist > 1e-9) {
            // Normal points from the robot toward the circle; robot moves the other way.
            nx = ex / dist;
            ny = ey / dist;
            push = circle.r - dist;
        } else {
            // Circle center is inside the robot: push out along the shallowest side.
            double penX = hl - Math.abs(lx);
            double penY = hw - Math.abs(ly);
            if (penX < penY) {
                nx = Math.signum(lx);
                ny = 0;
                push = penX + circle.r;
            } else {
                nx = 0;
                ny = Math.signum(ly);
                push = penY + circle.r;
            }
        }
        // Back to the field frame.
        double fnx = nx * c - ny * s;
        double fny = nx * s + ny * c;
        r.x -= fnx * push;
        r.y -= fny * push;
        removeVelocityInto(r, fnx, fny);
        return true;
    }

    /**
     * Robot against robot using the Separating Axis Test: two rectangles
     * overlap unless some edge direction separates them. The axis with the
     * smallest overlap is the easiest way out.
     */
    static boolean resolveRobot(SimRobot a, SimRobot b) {
        double[][] ca = a.corners();
        double[][] cb = b.corners();
        double[][] axes = {
            {Math.cos(a.heading), Math.sin(a.heading)},
            {-Math.sin(a.heading), Math.cos(a.heading)},
            {Math.cos(b.heading), Math.sin(b.heading)},
            {-Math.sin(b.heading), Math.cos(b.heading)},
        };
        double bestOverlap = Double.MAX_VALUE;
        double bestX = 0;
        double bestY = 0;
        for (double[] ax : axes) {
            double[] pa = project(ca, ax);
            double[] pb = project(cb, ax);
            double overlap = Math.min(pa[1], pb[1]) - Math.max(pa[0], pb[0]);
            if (overlap <= 0) {
                return false; // found a separating axis
            }
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestX = ax[0];
                bestY = ax[1];
            }
        }
        // Make the normal point from a toward b.
        if ((b.x - a.x) * bestX + (b.y - a.y) * bestY < 0) {
            bestX = -bestX;
            bestY = -bestY;
        }
        a.x -= bestX * bestOverlap;
        a.y -= bestY * bestOverlap;
        removeVelocityInto(a, bestX, bestY);
        return true;
    }

    private static double[] project(double[][] corners, double[] axis) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] c : corners) {
            double p = c[0] * axis[0] + c[1] * axis[1];
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        return new double[] {min, max};
    }

    /** Removes the part of the robot's velocity that points along (nx, ny). */
    private static void removeVelocityInto(SimRobot r, double nx, double ny) {
        double into = r.vx * nx + r.vy * ny;
        if (into > 0) {
            r.vx -= into * nx;
            r.vy -= into * ny;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

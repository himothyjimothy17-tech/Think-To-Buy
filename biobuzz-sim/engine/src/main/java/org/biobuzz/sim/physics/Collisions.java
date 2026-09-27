package org.biobuzz.sim.physics;

import org.biobuzz.sim.util.FastMath;

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

    /**
     * Resolves every robot against the field and against each other.
     * Robot pairs share the push by mass (a heavier robot moves less) and
     * their speeds toward each other become equal (a dead, inelastic bump).
     * Because each robot keeps driving into the other, the stronger/heavier
     * one wins the shoving match over time - like real pushing.
     */
    public static void resolveAll(List<SimRobot> robots, Field field) {
        for (int iter = 0; iter < 3; iter++) {
            for (SimRobot r : robots) {
                if (r.anchored) {
                    continue;
                }
                resolveWalls(r, field.half);
                for (Circle c : field.obstacles) {
                    resolveCircle(r, c);
                }
            }
            for (int i = 0; i < robots.size(); i++) {
                for (int j = i + 1; j < robots.size(); j++) {
                    resolvePair(robots.get(i), robots.get(j));
                }
            }
            for (SimRobot r : robots) {
                if (!r.anchored) {
                    resolveWalls(r, field.half);
                }
            }
        }
    }

    /** Two robots overlapping: split the separation by mass and kill their closing speed. */
    static boolean resolvePair(SimRobot a, SimRobot b) {
        if (a.anchored && b.anchored) {
            return false;
        }
        if (b.anchored) {
            return resolveRobot(a, b);
        }
        if (a.anchored) {
            return resolveRobot(b, a);
        }
        double[] n = overlapAxis(a, b);
        if (n == null) {
            return false;
        }
        double wa = 1 / a.massKg;
        double wb = 1 / b.massKg;
        double sa = wa / (wa + wb);
        double sb = wb / (wa + wb);
        a.x -= n[0] * n[2] * sa;
        a.y -= n[1] * n[2] * sa;
        b.x += n[0] * n[2] * sb;
        b.y += n[1] * n[2] * sb;
        double closing = (a.vx - b.vx) * n[0] + (a.vy - b.vy) * n[1];
        if (closing > 0) {
            double j = closing / (wa + wb); // impulse for a perfectly inelastic bump
            a.vx -= j * wa * n[0];
            a.vy -= j * wa * n[1];
            b.vx += j * wb * n[0];
            b.vy += j * wb * n[1];
        }
        return true;
    }

    /** Separating-axis test. Returns {nx, ny, overlap} with n pointing from a to b, or null. */
    static double[] overlapAxis(SimRobot a, SimRobot b) {
        double[][] ca = a.corners();
        double[][] cb = b.corners();
        double[][] axes = {
            {Math.cos(a.heading), Math.sin(a.heading)},
            {-Math.sin(a.heading), Math.cos(a.heading)},
            {Math.cos(b.heading), Math.sin(b.heading)},
            {-Math.sin(b.heading), Math.cos(b.heading)},
        };
        double best = Double.MAX_VALUE;
        double bx = 0;
        double by = 0;
        for (double[] ax : axes) {
            double[] pa = project(ca, ax);
            double[] pb = project(cb, ax);
            double overlap = Math.min(pa[1], pb[1]) - Math.max(pa[0], pb[0]);
            if (overlap <= 0) {
                return null;
            }
            if (overlap < best) {
                best = overlap;
                bx = ax[0];
                by = ax[1];
            }
        }
        if ((b.x - a.x) * bx + (b.y - a.y) * by < 0) {
            bx = -bx;
            by = -by;
        }
        return new double[] {bx, by, best};
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
        double dx = circle.x - r.x;
        double dy = circle.y - r.y;
        double reach = circle.r + (r.length + r.width) / 2; // >= half diagonal + radius
        if (dx * dx + dy * dy > reach * reach) {
            return false; // quick reject: too far to touch
        }
        double c = Math.cos(r.heading);
        double s = Math.sin(r.heading);
        // Circle center in the robot's frame.
        double lx = dx * c + dy * s;
        double ly = -dx * s + dy * c;
        double hl = r.length / 2;
        double hw = r.width / 2;
        // Closest point of the rectangle to the circle center.
        double px = clamp(lx, -hl, hl);
        double py = clamp(ly, -hw, hw);
        double ex = lx - px;
        double ey = ly - py;
        double dist = FastMath.hypot(ex, ey);
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

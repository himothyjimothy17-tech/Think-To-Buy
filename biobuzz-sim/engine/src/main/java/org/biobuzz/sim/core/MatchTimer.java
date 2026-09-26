package org.biobuzz.sim.core;

import org.biobuzz.sim.config.Cfg;

/**
 * The match clock: AUTO (30 s), TRANSITION (8 s), TELEOP (2:00) - §10.4.
 *
 * Stage 1 supports "practice" runs of a single period: starting a TeleOp
 * OpMode runs the TELEOP period, starting an Autonomous runs the AUTO period.
 * The full AUTO -> TRANSITION -> TELEOP match comes with scoring in stage 4.
 */
public final class MatchTimer {

    public enum Phase { PRE_MATCH, AUTO, TRANSITION, TELEOP, POST_MATCH }

    private final double autoSeconds;
    private final double transitionSeconds;
    private final double teleopSeconds;

    private Phase phase = Phase.PRE_MATCH;
    private double phaseStartSeconds;
    private double phaseLengthSeconds;

    public MatchTimer(Cfg game) {
        autoSeconds = game.num("match.autoSeconds");
        transitionSeconds = game.num("match.transitionSeconds");
        teleopSeconds = game.num("match.teleopSeconds");
    }

    public void reset() {
        phase = Phase.PRE_MATCH;
    }

    /** Starts a single practice period. */
    public void startPeriod(Phase p, double nowSeconds) {
        phase = p;
        phaseStartSeconds = nowSeconds;
        phaseLengthSeconds = lengthOf(p);
    }

    /** Moves to POST_MATCH when the period's time runs out. Returns true at that moment. */
    public boolean update(double nowSeconds) {
        if ((phase == Phase.AUTO || phase == Phase.TELEOP || phase == Phase.TRANSITION)
                && nowSeconds - phaseStartSeconds >= phaseLengthSeconds) {
            phase = Phase.POST_MATCH;
            return true;
        }
        return false;
    }

    public Phase phase() {
        return phase;
    }

    /** Seconds left in the current period (what the field display shows). */
    public double secondsLeft(double nowSeconds) {
        switch (phase) {
            case AUTO:
            case TRANSITION:
            case TELEOP:
                return Math.max(0, phaseLengthSeconds - (nowSeconds - phaseStartSeconds));
            case PRE_MATCH:
                return teleopSeconds;
            default:
                return 0;
        }
    }

    private double lengthOf(Phase p) {
        switch (p) {
            case AUTO: return autoSeconds;
            case TRANSITION: return transitionSeconds;
            case TELEOP: return teleopSeconds;
            default: return 0;
        }
    }
}

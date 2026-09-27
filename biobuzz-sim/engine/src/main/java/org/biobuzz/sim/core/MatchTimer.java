package org.biobuzz.sim.core;

import org.biobuzz.sim.config.Cfg;

/**
 * The match clock (§10.4): AUTO 30 s, TRANSITION 8 s, TELEOP 2:00.
 *
 * Two ways to run it:
 *   MATCH    - the real thing: AUTO -> TRANSITION -> TELEOP -> POST_MATCH
 *   PRACTICE - a single period (TELEOP for a TeleOp OpMode, AUTO for an
 *              Autonomous one), handy for driving practice
 */
public final class MatchTimer {

    public enum Phase { PRE_MATCH, AUTO, TRANSITION, TELEOP, POST_MATCH }

    public enum Mode { PRACTICE, MATCH }

    private final double autoSeconds;
    private final double transitionSeconds;
    private final double teleopSeconds;

    private Mode mode = Mode.PRACTICE;
    private Phase phase = Phase.PRE_MATCH;
    private double phaseStart;
    private double phaseLength;

    public MatchTimer(Cfg game) {
        autoSeconds = game.num("match.autoSeconds");
        transitionSeconds = game.num("match.transitionSeconds");
        teleopSeconds = game.num("match.teleopSeconds");
    }

    public void reset() {
        phase = Phase.PRE_MATCH;
        mode = Mode.PRACTICE;
    }

    /** Starts a full match at {@code now} (AUTO begins). */
    public void startMatch(double now) {
        mode = Mode.MATCH;
        enter(Phase.AUTO, now);
    }

    /** Starts a single practice period. */
    public void startPractice(Phase p, double now) {
        mode = Mode.PRACTICE;
        enter(p, now);
    }

    private void enter(Phase p, double now) {
        phase = p;
        phaseStart = now;
        switch (p) {
            case AUTO: phaseLength = autoSeconds; break;
            case TRANSITION: phaseLength = transitionSeconds; break;
            case TELEOP: phaseLength = teleopSeconds; break;
            default: phaseLength = Double.MAX_VALUE;
        }
    }

    /**
     * Advances the clock. Returns the phase that just STARTED, or null if
     * nothing changed this step.
     */
    public Phase update(double now) {
        if (phase == Phase.PRE_MATCH || phase == Phase.POST_MATCH) {
            return null;
        }
        if (now - phaseStart + 1e-9 < phaseLength) {
            return null;
        }
        Phase next;
        if (mode == Mode.PRACTICE) {
            next = Phase.POST_MATCH;
        } else if (phase == Phase.AUTO) {
            next = Phase.TRANSITION;
        } else if (phase == Phase.TRANSITION) {
            next = Phase.TELEOP;
        } else {
            next = Phase.POST_MATCH;
        }
        enter(next, phaseStart + phaseLength);
        return next;
    }

    public Phase phase() {
        return phase;
    }

    public Mode mode() {
        return mode;
    }

    /** Seconds since the current phase started. */
    public double phaseTime(double now) {
        return now - phaseStart;
    }

    /** Seconds left in the current period (what the field display shows). */
    public double secondsLeft(double now) {
        switch (phase) {
            case AUTO:
            case TRANSITION:
            case TELEOP:
                return Math.max(0, phaseLength - (now - phaseStart));
            case PRE_MATCH:
                return mode == Mode.MATCH ? autoSeconds : teleopSeconds;
            default:
                return 0;
        }
    }

    /** Seconds left in the whole MATCH (used for the "last 60 seconds" NECTAR rules). */
    public double matchSecondsLeft(double now) {
        switch (phase) {
            case AUTO: return secondsLeft(now) + transitionSeconds + teleopSeconds;
            case TRANSITION: return secondsLeft(now) + teleopSeconds;
            case TELEOP: return secondsLeft(now);
            case PRE_MATCH: return autoSeconds + transitionSeconds + teleopSeconds;
            default: return 0;
        }
    }

    public boolean isRunning() {
        return phase == Phase.AUTO || phase == Phase.TRANSITION || phase == Phase.TELEOP;
    }
}

package org.biobuzz.sim.robot;

import org.biobuzz.sim.game.Ball;

import java.util.List;

/** Anything that can hold POLLEN/NECTAR: our mechanisms, or another robot's. */
public interface ElementCarrier {
    /** How many elements the robot CONTROLS right now (G407: max 4). */
    int heldCount();

    List<Ball> heldBalls();
}

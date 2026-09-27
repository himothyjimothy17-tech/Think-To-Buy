package org.biobuzz.sim.game;

import org.biobuzz.sim.field.Alliance;

/** The three kinds of SCORING ELEMENT (§9.8). */
public enum ElementKind {
    POLLEN(null), RED_NECTAR(Alliance.RED), BLUE_NECTAR(Alliance.BLUE);

    /** For NECTAR, the alliance it belongs to; null for POLLEN. */
    public final Alliance alliance;

    ElementKind(Alliance alliance) {
        this.alliance = alliance;
    }

    public boolean isNectar() {
        return alliance != null;
    }

    public static ElementKind nectarOf(Alliance a) {
        return a == Alliance.RED ? RED_NECTAR : BLUE_NECTAR;
    }

    /** Short name used by the Limelight detector and the browser. */
    public String label() {
        switch (this) {
            case POLLEN: return "pollen";
            case RED_NECTAR: return "red_nectar";
            default: return "blue_nectar";
        }
    }
}

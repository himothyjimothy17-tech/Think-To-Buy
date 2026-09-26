package com.qualcomm.robotcore.hardware;

/** P, I, D gains. */
public class PIDCoefficients {
    public double p;
    public double i;
    public double d;

    public PIDCoefficients() {
    }

    public PIDCoefficients(double p, double i, double d) {
        this.p = p;
        this.i = i;
        this.d = d;
    }

    @Override
    public String toString() {
        return String.format("PIDCoefficients(p=%f i=%f d=%f)", p, i, d);
    }
}

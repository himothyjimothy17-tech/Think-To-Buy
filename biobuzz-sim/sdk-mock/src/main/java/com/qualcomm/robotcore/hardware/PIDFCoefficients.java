package com.qualcomm.robotcore.hardware;

/** P, I, D, F gains used by the hub's built-in motor controller. */
public class PIDFCoefficients {
    public double p;
    public double i;
    public double d;
    public double f;
    public MotorControlAlgorithm algorithm = MotorControlAlgorithm.PIDF;

    public PIDFCoefficients() {
    }

    public PIDFCoefficients(double p, double i, double d, double f, MotorControlAlgorithm algorithm) {
        this(p, i, d, f);
        this.algorithm = algorithm;
    }

    public PIDFCoefficients(double p, double i, double d, double f) {
        this.p = p;
        this.i = i;
        this.d = d;
        this.f = f;
    }

    public PIDFCoefficients(PIDFCoefficients other) {
        this(other.p, other.i, other.d, other.f, other.algorithm);
    }

    public PIDFCoefficients(PIDCoefficients other) {
        this(other.p, other.i, other.d, 0);
    }

    @Override
    public String toString() {
        return String.format("PIDFCoefficients(p=%f i=%f d=%f f=%f alg=%s)", p, i, d, f, algorithm);
    }
}

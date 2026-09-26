package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

/** A DC motor with an encoder port (matches the real FTC SDK). */
public interface DcMotor extends DcMotorSimple {

    enum ZeroPowerBehavior { UNKNOWN, BRAKE, FLOAT }

    enum RunMode {
        RUN_WITHOUT_ENCODER,
        RUN_USING_ENCODER,
        RUN_TO_POSITION,
        STOP_AND_RESET_ENCODER,
        /** @deprecated use RUN_WITHOUT_ENCODER */ @Deprecated RUN_WITHOUT_ENCODERS,
        /** @deprecated use RUN_USING_ENCODER */ @Deprecated RUN_USING_ENCODERS,
        /** @deprecated use STOP_AND_RESET_ENCODER */ @Deprecated RESET_ENCODERS;

        /** Converts the old deprecated names to the current ones. */
        @SuppressWarnings("deprecation")
        public RunMode migrate() {
            switch (this) {
                case RUN_WITHOUT_ENCODERS: return RUN_WITHOUT_ENCODER;
                case RUN_USING_ENCODERS: return RUN_USING_ENCODER;
                case RESET_ENCODERS: return STOP_AND_RESET_ENCODER;
                default: return this;
            }
        }

        public boolean isPIDMode() {
            RunMode m = migrate();
            return m == RUN_USING_ENCODER || m == RUN_TO_POSITION;
        }
    }

    MotorConfigurationType getMotorType();

    void setMotorType(MotorConfigurationType motorType);

    DcMotorController getController();

    int getPortNumber();

    void setZeroPowerBehavior(ZeroPowerBehavior zeroPowerBehavior);

    ZeroPowerBehavior getZeroPowerBehavior();

    @Deprecated
    void setPowerFloat();

    boolean getPowerFloat();

    void setTargetPosition(int position);

    int getTargetPosition();

    boolean isBusy();

    int getCurrentPosition();

    void setMode(RunMode mode);

    RunMode getMode();
}

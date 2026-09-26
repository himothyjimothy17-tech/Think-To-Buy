package com.qualcomm.robotcore.eventloop.opmode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a class as an autonomous OpMode shown in the Auto list. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Autonomous {
    String name() default "";

    String group() default "";

    /** Name of the TeleOp to queue up automatically after this auto. */
    String preselectTeleOp() default "";
}

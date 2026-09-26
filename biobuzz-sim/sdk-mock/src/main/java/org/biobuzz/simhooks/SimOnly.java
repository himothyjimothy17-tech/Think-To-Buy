package org.biobuzz.simhooks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class, constructor or method that exists ONLY in the simulator's
 * fake SDK, not in the real FTC SDK.
 *
 * Our TeamCode must never use anything marked @SimOnly - the SDK checker
 * tool (./gradlew sdkCheck) fails if it does, because that code would not
 * compile on the real robot.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
public @interface SimOnly {
}

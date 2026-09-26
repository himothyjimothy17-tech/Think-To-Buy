package com.qualcomm.robotcore.util;

/** Small math helpers: clip a number into a range, or rescale it. */
public class Range {

    /** Like the real SDK: only static methods, no instances. */
    private Range() {
    }

    /** Maps n from [x1, x2] to [y1, y2]. */
    public static double scale(double n, double x1, double x2, double y1, double y2) {
        double a = (y1 - y2) / (x1 - x2);
        double b = y1 - x1 * (y1 - y2) / (x1 - x2);
        return a * n + b;
    }

    public static double clip(double number, double min, double max) {
        return number < min ? min : (number > max ? max : number);
    }

    public static float clip(float number, float min, float max) {
        return number < min ? min : (number > max ? max : number);
    }

    public static int clip(int number, int min, int max) {
        return number < min ? min : (number > max ? max : number);
    }

    public static short clip(short number, short min, short max) {
        return number < min ? min : (number > max ? max : number);
    }

    public static byte clip(byte number, byte min, byte max) {
        return number < min ? min : (number > max ? max : number);
    }

    public static void throwIfRangeIsInvalid(double number, double min, double max) throws IllegalArgumentException {
        if (number < min || number > max) {
            throw new IllegalArgumentException(
                    String.format("number %f is invalid; valid ranges are %f..%f", number, min, max));
        }
    }

    public static void throwIfRangeIsInvalid(int number, int min, int max) throws IllegalArgumentException {
        if (number < min || number > max) {
            throw new IllegalArgumentException(
                    String.format("number %d is invalid; valid ranges are %d..%d", number, min, max));
        }
    }
}

package com.fst.naturaldisaster.field;

/**
 * Fixed 1024-entry unit-circle table.
 *
 * <p>The table is built once at class initialisation from the fixed rotation
 * {@code (cos(2*pi/1024), sin(2*pi/1024))}, using only multiplication, division and {@code sqrt}.
 * No trigonometric function is called at runtime: positions are required to be bit-identical across
 * platforms, and libm trig implementations are not.
 */
final class DirectionTable {
    static final int SIZE = 1024;
    private static final int MASK = SIZE - 1;

    /** cos(2*pi/1024) as a literal. */
    private static final double STEP_COS = 0.9999811752826011;
    /** sin(2*pi/1024) as a literal. */
    private static final double STEP_SIN = 0.006135884649154475;

    static final double[] COS = new double[SIZE];
    static final double[] SIN = new double[SIZE];

    static {
        double x = 1.0;
        double y = 0.0;
        for (int k = 0; k < SIZE; k++) {
            double inv = 1.0 / Math.sqrt(x * x + y * y);
            COS[k] = x * inv;
            SIN[k] = y * inv;
            double nx = x * STEP_COS - y * STEP_SIN;
            double ny = x * STEP_SIN + y * STEP_COS;
            x = nx;
            y = ny;
        }
    }

    private DirectionTable() {
    }

    /** Table index for a turn fraction in {@code [0, 1)}. */
    static int index(double turn) {
        return ((int) (turn * SIZE)) & MASK;
    }
}

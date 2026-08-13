package dev.aeronauticssimwheel.content;

/**
 * The fixed sim channel set of the SimWheel Control Block (DESIGN.md §5.3b,
 * decided 2026-08-13). Each channel binds to a redstone-link frequency
 * item-pair; analog channels transmit 0–15 levels, digital ones 15/0.
 * The signed steering axis splits across STEER_LEFT/STEER_RIGHT following
 * the community A/D convention seen in typewriter cars.
 */
public enum SimChannel {
    STEER_LEFT(true),
    STEER_RIGHT(true),
    THROTTLE(true),
    BRAKE(true),
    BTN_1(false),
    BTN_2(false),
    BTN_3(false),
    BTN_4(false);

    public final boolean analog;

    SimChannel(boolean analog) {
        this.analog = analog;
    }

    /** Digital button channels in order — BTN_1 is bit 0 of the button mask. */
    public static final SimChannel[] BUTTONS = {BTN_1, BTN_2, BTN_3, BTN_4};
}

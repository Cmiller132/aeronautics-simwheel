package dev.aeronauticssimwheel.content;

/**
 * The Sim Steering Wheel's redstone-link channel set (DESIGN.md §5.3). This is
 * the "everything that isn't the steering column" half of the block: steering
 * itself is native and float-precision (redstone side output + future direct
 * mount links); these channels carry every other input over Create's link
 * medium so the block can replace a linked typewriter in existing craft wiring.
 *
 * <p>Each channel binds to a redstone-link frequency item-pair; analog channels
 * transmit quantized 0–15, digital ones 15/0. {@code STEER_LEFT}/{@code
 * STEER_RIGHT} exist only for legacy link-steered craft (the community A/D
 * convention) and stay unbound on wheel-mount cars.
 */
public enum SimChannel {
    STEER_LEFT(true),
    STEER_RIGHT(true),
    THROTTLE(true),
    BRAKE(true),
    CLUTCH(true),
    BTN_1(false),
    BTN_2(false),
    BTN_3(false),
    BTN_4(false),
    BTN_5(false),
    BTN_6(false),
    BTN_7(false),
    BTN_8(false);

    public final boolean analog;

    SimChannel(boolean analog) {
        this.analog = analog;
    }

    /** Digital button channels in order — BTN_1 is bit 0 of the button mask. */
    public static final SimChannel[] BUTTONS = {BTN_1, BTN_2, BTN_3, BTN_4, BTN_5, BTN_6, BTN_7, BTN_8};
}

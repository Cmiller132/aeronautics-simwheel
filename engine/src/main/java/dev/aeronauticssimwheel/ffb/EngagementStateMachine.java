package dev.aeronauticssimwheel.ffb;

/**
 * Engagement lifecycle (DESIGN.md §5.2). Owned by the client's
 * EngagementController; pure JVM so every transition is unit-testable.
 *
 * <pre>
 * DISENGAGED ──(ENGAGE_PRESSED)──► ENGAGING
 * ENGAGING ──(ENGAGE_OK │ ENGAGE_TIMEOUT_CLIENT_ONLY)──► ENGAGED
 * ENGAGED ──(ENGAGE_PRESSED │ RANGE_EXIT │ WHEEL_REMOVED │ CRAFT_DISASSEMBLED
 *            │ GUI_OPENED │ FOCUS_LOST)──► DISENGAGING
 * DISENGAGING ──(STOP_SENT)──► DISENGAGED
 * any ──(FAULT_TRIP)──► FAULT ──(MANUAL_RESET only)──► DISENGAGED
 * </pre>
 */
public final class EngagementStateMachine {

    public enum State { DISENGAGED, ENGAGING, ENGAGED, DISENGAGING, FAULT }

    public enum Event {
        ENGAGE_PRESSED,
        ENGAGE_OK,
        ENGAGE_TIMEOUT_CLIENT_ONLY,
        RANGE_EXIT,
        WHEEL_REMOVED,
        CRAFT_DISASSEMBLED,
        GUI_OPENED,
        FOCUS_LOST,
        STOP_SENT,
        FAULT_TRIP,
        MANUAL_RESET
    }

    private State state = State.DISENGAGED;

    public State state() {
        return state;
    }

    /** Apply an event; illegal events for the current state are ignored. */
    public State on(Event event) {
        if (event == Event.FAULT_TRIP) {
            state = State.FAULT;
            return state;
        }
        state = switch (state) {
            case DISENGAGED -> event == Event.ENGAGE_PRESSED ? State.ENGAGING : state;
            case ENGAGING -> switch (event) {
                case ENGAGE_OK, ENGAGE_TIMEOUT_CLIENT_ONLY -> State.ENGAGED;
                case ENGAGE_PRESSED, RANGE_EXIT, WHEEL_REMOVED, CRAFT_DISASSEMBLED,
                     GUI_OPENED, FOCUS_LOST -> State.DISENGAGING;
                default -> state;
            };
            case ENGAGED -> switch (event) {
                case ENGAGE_PRESSED, RANGE_EXIT, WHEEL_REMOVED, CRAFT_DISASSEMBLED,
                     GUI_OPENED, FOCUS_LOST -> State.DISENGAGING;
                default -> state;
            };
            case DISENGAGING -> event == Event.STOP_SENT ? State.DISENGAGED : state;
            case FAULT -> event == Event.MANUAL_RESET ? State.DISENGAGED : state;
        };
        return state;
    }
}

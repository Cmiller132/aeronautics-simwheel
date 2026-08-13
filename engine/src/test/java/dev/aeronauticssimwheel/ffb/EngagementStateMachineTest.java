package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static dev.aeronauticssimwheel.ffb.EngagementStateMachine.Event;
import static dev.aeronauticssimwheel.ffb.EngagementStateMachine.State;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EngagementStateMachineTest {

    @Test
    void happyPath() {
        EngagementStateMachine m = new EngagementStateMachine();
        assertEquals(State.DISENGAGED, m.state());
        assertEquals(State.ENGAGING, m.on(Event.ENGAGE_PRESSED));
        assertEquals(State.ENGAGED, m.on(Event.ENGAGE_OK));
        assertEquals(State.DISENGAGING, m.on(Event.ENGAGE_PRESSED));
        assertEquals(State.DISENGAGED, m.on(Event.STOP_SENT));
    }

    @Test
    void timeoutFallsBackToClientOnlyEngagement() {
        EngagementStateMachine m = new EngagementStateMachine();
        m.on(Event.ENGAGE_PRESSED);
        assertEquals(State.ENGAGED, m.on(Event.ENGAGE_TIMEOUT_CLIENT_ONLY));
    }

    @Test
    void guiFocusAndRangeAllDisengage() {
        for (Event e : new Event[]{Event.GUI_OPENED, Event.FOCUS_LOST, Event.RANGE_EXIT,
                Event.WHEEL_REMOVED, Event.CRAFT_DISASSEMBLED}) {
            EngagementStateMachine m = new EngagementStateMachine();
            m.on(Event.ENGAGE_PRESSED);
            m.on(Event.ENGAGE_OK);
            assertEquals(State.DISENGAGING, m.on(e), "event " + e + " must disengage");
        }
    }

    @Test
    void faultLatchesAndOnlyManualResetLeaves() {
        EngagementStateMachine m = new EngagementStateMachine();
        m.on(Event.ENGAGE_PRESSED);
        m.on(Event.ENGAGE_OK);
        assertEquals(State.FAULT, m.on(Event.FAULT_TRIP));
        // Nothing else gets out of FAULT
        for (Event e : new Event[]{Event.ENGAGE_PRESSED, Event.ENGAGE_OK, Event.STOP_SENT,
                Event.GUI_OPENED, Event.RANGE_EXIT}) {
            assertEquals(State.FAULT, m.on(e), "event " + e + " must not clear FAULT");
        }
        assertEquals(State.DISENGAGED, m.on(Event.MANUAL_RESET));
    }

    @Test
    void illegalEventsAreIgnored() {
        EngagementStateMachine m = new EngagementStateMachine();
        assertEquals(State.DISENGAGED, m.on(Event.ENGAGE_OK));
        assertEquals(State.DISENGAGED, m.on(Event.STOP_SENT));
        assertEquals(State.DISENGAGED, m.on(Event.MANUAL_RESET));
    }
}

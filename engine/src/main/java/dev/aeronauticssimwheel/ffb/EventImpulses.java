package dev.aeronauticssimwheel.ffb;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-side renderer for the low-latency FFB event path (DESIGN.md §6.3 /
 * Phase 2a): strikes (curb hits, hard landings) arrive as tiny immediate
 * packets instead of riding the 20 Hz telemetry batch, and are rendered
 * locally as exponentially decaying torque impulses summed into the mixer's
 * local-feel input.
 *
 * <p>Pure JVM, single-writer (FFB thread) with events posted from the network
 * thread through a bounded queue — a burst of malformed/hostile events can
 * never grow memory or output unboundedly: the queue caps at
 * {@value #MAX_PENDING} and active impulses at {@value #MAX_ACTIVE}; the
 * SafetyChain still clamps whatever this produces.
 */
public final class EventImpulses {

    public static final int MAX_PENDING = 32;
    public static final int MAX_ACTIVE = 8;
    /** Default decay time constant — matches the seam-strike model (§10.5). */
    public static final double DEFAULT_TAU_S = 0.035;

    public record Event(float peakNm, double tauSeconds) {
    }

    private final Deque<Event> pending = new ArrayDeque<>();
    private final Impulse[] active = new Impulse[MAX_ACTIVE];
    private int activeCount;

    private static final class Impulse {
        float amplitudeNm;
        double tauSeconds;
    }

    public EventImpulses() {
        for (int i = 0; i < MAX_ACTIVE; i++) {
            active[i] = new Impulse();
        }
    }

    /** Network thread: post an event. Silently dropped when the queue is full. */
    public synchronized void post(float peakNm, double tauSeconds) {
        if (pending.size() < MAX_PENDING && tauSeconds > 0 && Float.isFinite(peakNm)) {
            pending.add(new Event(peakNm, tauSeconds));
        }
    }

    /**
     * FFB thread: advance decay by dt and return the summed impulse torque.
     * Newly posted events start at full amplitude on the step they are absorbed.
     */
    public float step(double dtSeconds) {
        absorbPending();
        float sum = 0f;
        int write = 0;
        for (int i = 0; i < activeCount; i++) {
            Impulse imp = active[i];
            imp.amplitudeNm *= (float) Math.exp(-dtSeconds / imp.tauSeconds);
            if (Math.abs(imp.amplitudeNm) > 0.001f) {
                sum += imp.amplitudeNm;
                Impulse keep = active[write];
                active[write] = imp;
                active[i] = keep;
                write++;
            }
        }
        activeCount = write;
        return sum;
    }

    private synchronized void absorbPending() {
        Event e;
        while ((e = pending.poll()) != null) {
            if (activeCount == MAX_ACTIVE) {
                // Replace the weakest active impulse — a fresh strike matters more
                int weakest = 0;
                for (int i = 1; i < MAX_ACTIVE; i++) {
                    if (Math.abs(active[i].amplitudeNm) < Math.abs(active[weakest].amplitudeNm)) {
                        weakest = i;
                    }
                }
                active[weakest].amplitudeNm = e.peakNm();
                active[weakest].tauSeconds = e.tauSeconds();
            } else {
                active[activeCount].amplitudeNm = e.peakNm();
                active[activeCount].tauSeconds = e.tauSeconds();
                activeCount++;
            }
        }
    }

    public int activeCount() {
        absorbPending();
        return activeCount;
    }
}

package dev.aeronauticssimwheel.network;

/**
 * Per-packet-type ingress budget (adversarial-review hardening): a hostile or
 * buggy server must not be able to flood the client main-thread executor with
 * queued work — the bounded buffers downstream only cap storage, not queued
 * tasks. Checked on the network thread BEFORE {@code enqueueWork}; excess
 * packets are dropped silently (telemetry is a stream — losing floods is the
 * correct failure mode, and the TelemetryBuffer fades on gaps by design).
 */
final class PacketRateGate {

    private final int maxPerWindow;
    private long windowStartNanos = System.nanoTime();
    private int count;

    PacketRateGate(int maxPerSecond) {
        this.maxPerWindow = maxPerSecond;
    }

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        if (now - windowStartNanos > 1_000_000_000L) {
            windowStartNanos = now;
            count = 0;
        }
        return ++count <= maxPerWindow;
    }
}

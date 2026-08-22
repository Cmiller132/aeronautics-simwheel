package dev.aeronauticssimwheel.network;

/**
 * Per-packet-type ingress budget (adversarial-review hardening): a hostile or
 * buggy server must not be able to flood the client main-thread executor with
 * queued work — the bounded buffers downstream only cap storage, not queued
 * tasks. Checked on the network thread BEFORE {@code enqueueWork}; excess
 * packets are dropped silently (telemetry is a stream — losing floods is the
 * correct failure mode, and the TelemetryBuffer fades on gaps by design).
 *
 * <p>Token bucket, not a fixed window: a fixed window admits up to 2× the
 * nominal budget in a burst straddling the window boundary. Capacity equals
 * one second of budget, refilled continuously.
 */
final class PacketRateGate {

    private final double maxPerSecond;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    PacketRateGate(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
        this.tokens = maxPerSecond;
    }

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsedS = (now - lastRefillNanos) / 1e9;
        lastRefillNanos = now;
        tokens = Math.min(maxPerSecond, tokens + elapsedS * maxPerSecond);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}

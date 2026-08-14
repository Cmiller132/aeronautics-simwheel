package dev.aeronauticssimwheel.ffb;

/**
 * The complete shipping FFB composition (DESIGN.md §6.5): reconstructed tire
 * telemetry ({@link TelemetryBuffer}) + soft lock + damper + friction + strike
 * impulses ({@link EventImpulses}), soft-knee mixed ({@link Mixer}), with the
 * {@link SafetyChain} last and unbypassable. This one class is what the mod's
 * FFB thread runs <em>and</em> what the offline harness and unit tests drive —
 * one composition, one truth.
 *
 * <p>Threading: {@link #step} (and {@link #panic}) run on exactly one thread —
 * the FFB loop, or the single-threaded harness. {@link #postTelemetry},
 * {@link #noteTelemetryBatch}, {@link #postEvent} and {@link #setTuning} may be
 * called from any thread; ingress hygiene (clamps, NaN rejection — adversarial
 * findings, see the constants) lives here so every driver gets it.
 *
 * <p>Engage edges are derived inside {@link #step} from the {@code engaged}
 * input, so all SafetyChain state stays confined to the step thread (the old
 * game-thread engage()/FFB-thread step() split was an undeclared data race).
 * A latched FAULT is cleared only by a disengage — so after a panic, forces
 * return exactly one deliberate re-engage later, never on their own (§7).
 */
public final class FfbPipeline {

    /** Hostile-server hygiene on the event path (SafetyChain still follows). */
    public static final float MAX_EVENT_PEAK_NM = 3.0f;
    /**
     * Ingress clamp for telemetry samples: far above any legitimate signal,
     * small enough that no downstream float arithmetic (interpolation deltas,
     * mixing) can overflow — two wire-legal ±Float.MAX samples would otherwise
     * reach ±Inf inside the buffer (adversarial finding).
     */
    public static final float MAX_TELEMETRY_NM = 50.0f;
    /** EMA gain for the server→client clock mapping (~1 s time constant at 20 Hz). */
    private static final double CLOCK_EMA_ALPHA = 0.05;

    /** Last mix breakdown, for the HUD and the harness trace. */
    public record Components(float telemetryNm, float softLockNm, float damperNm,
                             float frictionNm, float impulseNm) {
        public static final Components ZERO = new Components(0f, 0f, 0f, 0f, 0f);
    }

    private final TelemetryBuffer telemetry = new TelemetryBuffer();
    private final EventImpulses impulses = new EventImpulses();

    private volatile FfbTuning requestedTuning;
    private volatile FfbTuning tuning; // volatile only for HUD reads; written on the step thread
    private SafetyChain safety;
    private SoftLock softLock;
    private Mixer mixer;

    private boolean wasEngaged;
    /** Server-timeline − client-monotonic clock offset (s); NaN until known. */
    private volatile double serverClockOffsetS = Double.NaN;
    private volatile float lastOutputNm;
    private volatile Components lastComponents = Components.ZERO;

    public FfbPipeline() {
        this(FfbTuning.defaults());
    }

    public FfbPipeline(FfbTuning initial) {
        FfbTuning t = initial.sanitized();
        this.tuning = t;
        this.safety = new SafetyChain(t.safetyConfig());
        this.softLock = new SoftLock(t.lockConfig());
        this.mixer = new Mixer(t.maxTorqueNm() * t.kneeFraction(), t.kneeRatio());
    }

    /**
     * Any thread; applied at the next step. Feel gains change live; a change to
     * any SafetyChain parameter rebuilds the chain, which re-ramps from zero —
     * a config edit can cause a dip, never a spike. A latched FAULT survives.
     */
    public void setTuning(FfbTuning t) {
        requestedTuning = t.sanitized();
    }

    /** One telemetry sample on the server timeline. Non-finite input is dropped. */
    public void postTelemetry(double serverTimeS, float torqueNm) {
        if (!Double.isFinite(serverTimeS) || !Float.isFinite(torqueNm)) {
            return;
        }
        telemetry.addSample(serverTimeS, Math.clamp(torqueNm, -MAX_TELEMETRY_NM, MAX_TELEMETRY_NM));
    }

    /**
     * Once per telemetry batch: update the server→client clock mapping (EMA'd
     * so tick jitter doesn't wobble the playback point — the buffer's 75 ms
     * delay absorbs the residual).
     */
    public void noteTelemetryBatch(double lastSampleServerTimeS, double clientNowS) {
        if (!Double.isFinite(lastSampleServerTimeS) || !Double.isFinite(clientNowS)) {
            return;
        }
        double target = lastSampleServerTimeS - clientNowS;
        double offset = serverClockOffsetS;
        serverClockOffsetS = Double.isNaN(offset)
                ? target : offset + CLOCK_EMA_ALPHA * (target - offset);
    }

    /** Contact strike: clamp, then hand to the decaying-impulse renderer. */
    public void postEvent(float peakNm, double tauSeconds) {
        if (!Float.isFinite(peakNm) || !Double.isFinite(tauSeconds)) {
            return;
        }
        impulses.post(Math.clamp(peakNm, -MAX_EVENT_PEAK_NM, MAX_EVENT_PEAK_NM),
                Math.clamp(tauSeconds, 0.005, 0.5));
    }

    /**
     * One FFB step.
     *
     * @param engaged      rig engagement; edges are handled here (engage ramps
     *                     in, disengage slews out and clears all rig state)
     * @param clientNowS   monotonic client clock in seconds — the same clock
     *                     {@link #noteTelemetryBatch} was fed
     * @param hwDeg        hardware wheel angle (bridge STATE when available,
     *                     commanded angle otherwise)
     * @param hwVelDegPerS hardware wheel angular velocity
     * @param lockDeg      the block's configured half-lock for the soft stop
     * @param inputFresh   false when the game-side snapshot is stale (§7.4)
     * @param dtSeconds    time since the previous step
     * @return torque to write to the device, in Nm
     */
    public float step(boolean engaged, double clientNowS, double hwDeg, double hwVelDegPerS,
                      double lockDeg, boolean inputFresh, double dtSeconds) {
        FfbTuning req = requestedTuning;
        if (req != null && req != tuning) {
            applyTuning(req);
        }

        if (engaged != wasEngaged) {
            if (engaged) {
                safety.engage();
            } else {
                if (safety.state() == SafetyChain.State.FAULT) {
                    safety.reset(); // the deliberate disengage IS the fault acknowledgement
                } else {
                    safety.disengage();
                }
                // Rig teardown: a strike or stale telemetry from this vehicle
                // must not survive into the next engagement (or next server).
                impulses.clear();
                telemetry.clear();
                serverClockOffsetS = Double.NaN;
            }
            wasEngaged = engaged;
        }

        float telemetryNm = 0f;
        float lockNm = 0f;
        float damperNm = 0f;
        float frictionNm = 0f;
        float impulseNm = 0f;
        float requested = 0f;
        if (engaged) {
            double offset = serverClockOffsetS;
            if (!Double.isNaN(offset)) {
                telemetryNm = telemetry.sample(clientNowS + offset) * tuning.telemetryGain();
            }
            lockNm = softLock.torqueNm(hwDeg, hwVelDegPerS, lockDeg);
            damperNm = FeelEffects.damper(tuning.damperNmPerDegPerS(), 1.0, 1.0, hwVelDegPerS);
            frictionNm = FeelEffects.friction(tuning.frictionNm(),
                    tuning.frictionEpsDegPerS(), hwVelDegPerS);
            impulseNm = impulses.step(dtSeconds);
            requested = mixer.mix(telemetryNm, lockNm, damperNm, frictionNm, impulseNm);
        }

        float out = safety.step(requested, dtSeconds, inputFresh);
        lastComponents = new Components(telemetryNm, lockNm, damperNm, frictionNm, impulseNm);
        lastOutputNm = out;
        return out;
    }

    /** Step-thread only: immediate zero + latched FAULT. Cleared by a disengage. */
    public void panic() {
        safety.panic();
        lastOutputNm = 0f;
        lastComponents = Components.ZERO;
    }

    private void applyTuning(FfbTuning next) {
        boolean safetyChanged = tuning.safetyDiffers(next);
        tuning = next;
        softLock = new SoftLock(next.lockConfig());
        mixer = new Mixer(next.maxTorqueNm() * next.kneeFraction(), next.kneeRatio());
        if (safetyChanged) {
            SafetyChain.State prior = safety.state();
            safety = new SafetyChain(next.safetyConfig());
            switch (prior) {
                case FAULT -> safety.panic();    // a tuning edit never clears a fault
                case ENGAGED -> safety.engage(); // re-ramp from zero: dip, never spike
                case DISENGAGED -> { }
            }
        }
    }

    public float lastOutputNm() {
        return lastOutputNm;
    }

    public Components lastComponents() {
        return lastComponents;
    }

    /** True while telemetry playback is serving stale/faded data (HUD). */
    public boolean telemetryStale() {
        return telemetry.isStale();
    }

    public SafetyChain.State safetyState() {
        return safety.state();
    }

    /** The tuning currently applied (HUD/status). */
    public FfbTuning tuning() {
        return tuning;
    }
}

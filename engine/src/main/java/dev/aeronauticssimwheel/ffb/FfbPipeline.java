package dev.aeronauticssimwheel.ffb;

/**
 * The complete shipping FFB composition (DESIGN.md §6.5): reconstructed
 * component telemetry ({@link TelemetryBuffer} carrying {@link TelemetryFrame}s)
 * composed client-side — understeer trail collapse on the SAT channel,
 * differential texture, surface/drivetrain synthesis, speed-scaled damper and
 * parking friction — plus soft lock and strike impulses, soft-knee mixed with
 * the lock outside the knee ({@link Mixer}), with the {@link SafetyChain} last
 * and unbypassable. This one class is what the mod's FFB thread runs <em>and</em>
 * what the offline harness and unit tests drive — one composition, one truth.
 *
 * <p>Threading: {@link #step} (and {@link #panic}) run on exactly one thread —
 * the FFB loop, or the single-threaded harness. {@link #postTelemetry},
 * {@link #noteTelemetryBatch}, {@link #postEvent}, {@link #setTuning} and
 * {@link #setTestSignal} may be called from any thread; ingress hygiene
 * (clamps, NaN rejection) lives here so every driver gets it.
 *
 * <p>Engage edges are derived inside {@link #step} from the {@code engaged}
 * input, so all SafetyChain state stays confined to the step thread. A latched
 * FAULT is cleared only by a disengage — so after a panic, forces return
 * exactly one deliberate re-engage later, never on their own (§7).
 */
public final class FfbPipeline {

    /** Hostile-server hygiene on the event path (SafetyChain still follows). */
    public static final float MAX_EVENT_PEAK_NM = 3.0f;
    /** EMA gain for the server→client clock mapping (~1 s time constant at 20 Hz). */
    private static final double CLOCK_EMA_ALPHA = 0.05;
    /** EMA gain for batch-arrival jitter (adaptive playback delay). */
    private static final double JITTER_EMA_ALPHA = 0.1;

    /** Commissioning test signals (§10.5): measured through the full chain. */
    public enum TestSignal { NONE, SWEEP, STEP }

    private static final float TEST_SWEEP_AMP_NM = 0.5f;
    private static final double TEST_SWEEP_F0_HZ = 0.5;
    private static final double TEST_SWEEP_F1_HZ = 16.0;
    private static final double TEST_SWEEP_PERIOD_S = 20.0;
    private static final float TEST_STEP_AMP_NM = 0.6f;
    private static final double TEST_STEP_HOLD_S = 1.0;

    /** Last mix breakdown, for the HUD and the harness trace. */
    public record Components(float satNm, float textureNm, float synthNm, float rumbleNm,
                             float damperNm, float frictionNm, float impulseNm, float lockNm) {
        public static final Components ZERO =
                new Components(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

        /** Reconstructed-telemetry torque (wire content after client gains). */
        public float telemetryNm() {
            return satNm + textureNm;
        }
    }

    private final TelemetryBuffer telemetry = new TelemetryBuffer();
    private final EventImpulses impulses = new EventImpulses();
    private final SurfaceTexture surface = new SurfaceTexture(0x5EED5EEDL);
    private final DrivetrainRumble rumble = new DrivetrainRumble();
    private final float[] frameBuf = new float[TelemetryFrame.CHANNELS];

    private volatile FfbTuning requestedTuning;
    private volatile FfbTuning tuning; // volatile only for HUD reads; written on the step thread
    private SafetyChain safety;
    private SoftLock softLock;
    private Mixer mixer;

    private boolean wasEngaged;
    /** Server-timeline − client-monotonic clock offset (s); NaN until known. */
    private volatile double serverClockOffsetS = Double.NaN;
    private volatile double batchJitterS = 0.010; // conservative until measured
    private volatile float lastOutputNm;
    private volatile Components lastComponents = Components.ZERO;
    private volatile TelemetryFrame lastFrame = TelemetryFrame.ZERO;

    private volatile TestSignal testSignal = TestSignal.NONE;
    private TestSignal activeTest = TestSignal.NONE;
    private double testTimeS;

    public FfbPipeline() {
        this(FfbTuning.defaults());
    }

    public FfbPipeline(FfbTuning initial) {
        FfbTuning t = initial.sanitized();
        this.tuning = t;
        this.safety = new SafetyChain(t.safetyConfig());
        this.softLock = new SoftLock(t.lockConfig());
        this.mixer = new Mixer(t.maxTorqueNm() * t.kneeFraction(), t.kneeRatio());
        applyDelayPolicy(t);
    }

    /**
     * Any thread; applied at the next step. Feel gains change live; a change to
     * any SafetyChain parameter rebuilds the chain, which re-ramps from zero —
     * a config edit can cause a dip, never a spike. A latched FAULT survives.
     */
    public void setTuning(FfbTuning t) {
        requestedTuning = t.sanitized();
    }

    /**
     * Any thread: select a commissioning test signal. While active (and
     * engaged) the feel content is replaced by the generator so the chain's
     * response can be measured; the soft lock and the SafetyChain stay live.
     */
    public void setTestSignal(TestSignal signal) {
        testSignal = signal == null ? TestSignal.NONE : signal;
    }

    public TestSignal testSignal() {
        return testSignal;
    }

    /** One telemetry frame on the server timeline. Non-finite fields are neutralized. */
    public void postTelemetry(double serverTimeS, TelemetryFrame frame) {
        if (!Double.isFinite(serverTimeS) || frame == null) {
            return;
        }
        telemetry.addSample(serverTimeS, frame.sanitizedForIngress());
    }

    /**
     * Once per telemetry batch: update the server→client clock mapping (EMA'd
     * so tick jitter doesn't wobble the playback point) and the arrival-jitter
     * estimate that drives the adaptive playback delay (§6.4).
     */
    public void noteTelemetryBatch(double lastSampleServerTimeS, double clientNowS) {
        if (!Double.isFinite(lastSampleServerTimeS) || !Double.isFinite(clientNowS)) {
            return;
        }
        double target = lastSampleServerTimeS - clientNowS;
        double offset = serverClockOffsetS;
        if (Double.isNaN(offset)) {
            serverClockOffsetS = target;
            return;
        }
        double err = Math.abs(target - offset);
        batchJitterS += JITTER_EMA_ALPHA * (Math.min(err, 0.25) - batchJitterS);
        serverClockOffsetS = offset + CLOCK_EMA_ALPHA * (target - offset);
        FfbTuning t = tuning;
        if (t.playbackDelayMs() <= 0f) {
            // Adaptive: one tick of batching floor plus 4σ-ish jitter headroom.
            telemetry.setPlaybackDelayS(0.025 + 4.0 * batchJitterS);
        }
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
                surface.reset();
                rumble.reset();
                serverClockOffsetS = Double.NaN;
            }
            wasEngaged = engaged;
        }

        Components components = Components.ZERO;
        float requested = 0f;
        if (engaged) {
            TestSignal test = testSignal;
            if (test != activeTest) {
                activeTest = test;
                testTimeS = 0;
            }
            float lockNm = softLock.torqueNm(hwDeg, hwVelDegPerS, lockDeg);
            if (test != TestSignal.NONE) {
                // Commissioning mode: the generator replaces feel content so the
                // chain's response is measurable; lock + SafetyChain stay live.
                testTimeS += dtSeconds;
                requested = testTorqueNm(test) + lockNm;
                components = new Components(0f, 0f, 0f, 0f, 0f, 0f, 0f, lockNm);
            } else {
                FfbTuning t = tuning;
                double offset = serverClockOffsetS;
                TelemetryFrame frame = TelemetryFrame.ZERO;
                boolean stale = false;
                if (!Double.isNaN(offset)) {
                    telemetry.sample(clientNowS + offset, frameBuf);
                    stale = telemetry.isStale();
                    frame = TelemetryFrame.fromArray(frameBuf);
                }
                lastFrame = frame;

                // Understeer: collapse the rendered trail as the steered axle
                // slides — the limit-grip lightness the linear tire can't produce.
                float satNm = frame.satNm() * understeerScale(t, frame.slip())
                        * t.telemetryGain();
                float textureNm = frame.textureNm() * t.textureGain();
                // Synths are context-keyed: mute on stale context, never fabricate.
                float synthNm = stale ? 0f
                        : surface.step(dtSeconds, frame.speedMS(), frame.mu(), t.surfaceTextureNm());
                float rumbleNm = stale ? 0f
                        : rumble.step(dtSeconds, frame.driveRpm(), t.rumbleNm());
                float damperNm = FeelEffects.damper(t.damperNmPerDegPerS(), frame.speedMS(),
                        t.damperFloor(), t.damperSpeedRefMS(), hwVelDegPerS);
                float frictionScale = stale ? 1f
                        : FeelEffects.parkingScale(frame.speedMS(), t.parkingBoost(),
                        t.parkingSpeedMS());
                float frictionNm = FeelEffects.friction(t.frictionNm() * frictionScale,
                        t.frictionEpsDegPerS(), hwVelDegPerS);
                float impulseNm = impulses.step(dtSeconds);

                float feelNm = satNm + textureNm + synthNm + rumbleNm
                        + damperNm + frictionNm + impulseNm;
                requested = mixer.mix(feelNm, lockNm);
                components = new Components(satNm, textureNm, synthNm, rumbleNm,
                        damperNm, frictionNm, impulseNm, lockNm);
            }
        }

        float out = safety.step(requested, dtSeconds, inputFresh);
        lastComponents = components;
        lastOutputNm = out;
        return out;
    }

    /** Step-thread only: immediate zero + latched FAULT. Cleared by a disengage. */
    public void panic() {
        safety.panic();
        lastOutputNm = 0f;
        lastComponents = Components.ZERO;
    }

    private float testTorqueNm(TestSignal test) {
        return switch (test) {
            case SWEEP -> {
                // Logarithmic sweep, looping: phase integral of f(t) = f0·(f1/f0)^(t/T)
                double u = (testTimeS % TEST_SWEEP_PERIOD_S) / TEST_SWEEP_PERIOD_S;
                double k = Math.log(TEST_SWEEP_F1_HZ / TEST_SWEEP_F0_HZ);
                double phase = 2 * Math.PI * TEST_SWEEP_F0_HZ * TEST_SWEEP_PERIOD_S
                        * (Math.exp(k * u) - 1) / k;
                yield (float) (TEST_SWEEP_AMP_NM * Math.sin(phase));
            }
            case STEP -> ((long) (testTimeS / TEST_STEP_HOLD_S)) % 2 == 0
                    ? TEST_STEP_AMP_NM : -TEST_STEP_AMP_NM;
            case NONE -> 0f;
        };
    }

    private static float understeerScale(FfbTuning t, float slip) {
        if (t.understeerDepth() <= 0f) {
            return 1f;
        }
        double x = (slip - t.understeerSlipStart())
                / (t.understeerSlipFull() - t.understeerSlipStart());
        double s = Math.clamp(x, 0.0, 1.0);
        s = s * s * (3.0 - 2.0 * s); // smoothstep
        return (float) (1.0 - t.understeerDepth() * s);
    }

    private void applyTuning(FfbTuning next) {
        boolean safetyChanged = tuning.safetyDiffers(next);
        tuning = next;
        softLock = new SoftLock(next.lockConfig());
        mixer = new Mixer(next.maxTorqueNm() * next.kneeFraction(), next.kneeRatio());
        applyDelayPolicy(next);
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

    private void applyDelayPolicy(FfbTuning t) {
        if (t.playbackDelayMs() > 0f) {
            telemetry.setPlaybackDelayS(t.playbackDelayMs() / 1000.0);
        }
        // 0 = adaptive: noteTelemetryBatch keeps steering it from jitter.
    }

    public float lastOutputNm() {
        return lastOutputNm;
    }

    public Components lastComponents() {
        return lastComponents;
    }

    /** Last reconstructed frame (HUD: speed/slip/μ/rpm). */
    public TelemetryFrame lastFrame() {
        return lastFrame;
    }

    /** Current playback delay in seconds (HUD). */
    public double playbackDelayS() {
        return telemetry.playbackDelayS();
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

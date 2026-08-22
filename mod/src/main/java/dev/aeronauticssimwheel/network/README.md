# network/ — three packets and a rate gate

The complete wire surface between client and server. Everything else syncs
through the BE update tag.

## Files

| File | Direction | Role |
|---|---|---|
| `SimWheelInputPacket.java` | client → server | One hardware input frame: `BlockPos`, steering −1…1 of the block's lock, pedals 0…1, button mask. Sent on change + 10-tick heartbeat. Server-side validation: finite values only, sender riding something, within 8 blocks of the wheel's **pose-transformed** position (moving craft validate correctly), the driver latch (DESIGN.md §5.2). |
| `FfbTelemetryPacket.java` | server → client | Once per game tick while a rig is live: base server-time + each sample's true offset + its **component frame** (SAT at reference trail, differential texture, speed, slip, μ, rpm — ≤64 samples). Composition happens client-side where it's hot-reload tunable. The irFFB trick — the client's `TelemetryBuffer` reconstructs a 40+ Hz signal from 20 Hz packets. Mis-ordered offsets are clamped, not thrown (a decode throw would drop the connection over a feel packet). |
| `FfbEventPacket.java` | server → client | Immediate: one contact transient (curb strike, landing, craft collision) bypassing the batch — **bipolar**, signed by originating side. Strictly a local-synthesis trigger — peak clamped at send *and* on receipt, rendered as a decaying impulse, SafetyChain downstream. |
| `PacketRateGate.java` | — | Per-sender (server side) / per-type (client side) ingress budget, checked on the **network thread before `enqueueWork`** — floods are dropped before they can queue main-thread work, because the bounded buffers downstream only cap storage, not queued tasks. Token bucket (a fixed window would admit 2× bursts at window boundaries). Sender gates are evicted on logout. |

## Rules

- Every handler validates before it enqueues; malformed or hostile input is
  dropped silently (log-throttled), never thrown.
- Nothing here trusts the peer: the client clamps server torque values at
  ingress (a hostile server can make the wheel feel bad, never unsafe), the
  server re-validates every input frame (a hostile client can't exceed a
  ±1 float or bypass the seat mutex).

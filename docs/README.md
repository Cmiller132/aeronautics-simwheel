# docs/

| File | What it is | Read it when |
|---|---|---|
| [`DESIGN.md`](DESIGN.md) | The architecture + decision record: the system as built, with the decisions folded in. §2 is the load-bearing table — every reach into upstream code, each health-checked. §3 the system overview, §5 the block, §6 the FFB path, §7 safety, §11 history/roadmap. | Before changing anything structural, or to find out *why* something is the way it is. |
| [`RESEARCH.md`](RESEARCH.md) | The source-verified findings under the decisions: the Simulated/Sable/Offroad ecosystem, how their control paths work, MOZA R9 + FFB-from-Java routes, the wheel-mount physics formulas. Historical log with "Outcome" notes where a finding became a build. | To check an upstream fact, or before re-litigating a decision — the alternatives were mapped. |
| [`RELEASING.md`](RELEASING.md) | How a release is built and published: version bump points, artifact build order, the carry-forward rule for bridge binaries, the `gh` commands. | When cutting a release. |
| `releases/` | Release notes per version, committed before the tag. | — |

Tester-facing docs live at the repo root ([`README.md`](../README.md),
[`TESTING.md`](../TESTING.md)); per-package code maps live next to the code
they map (see the root README's repository map).

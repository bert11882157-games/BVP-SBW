# BVP dependency boundary

No dependency JARs are committed here. The inherited Gradle metadata currently names:

| Dependency | Existing declaration |
| --- | --- |
| Minecraft / Forge | 1.20.1 / 47.4.20 |
| Superb Warfare | Legacy flat-directory name; release compilation needs the matching customized fork |
| GeckoLib | 4.4.6 for Forge 1.20.1 |
| SBW Mesh Loader | 0.1.1 |
| Simple Bedrock Model | 2.3.3-forge-mc1.20.1 |
| MAE | 1.1.2 |

The legacy MDK dependency name is retained for review, not asserted to reproduce the current
production build. See [build status](../../docs/BUILDING.md) for the runtime/SRG classpath gap.
Obtain dependencies through their supported distribution channels and applicable permissions.

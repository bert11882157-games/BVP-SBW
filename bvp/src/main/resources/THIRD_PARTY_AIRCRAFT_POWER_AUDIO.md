# Aircraft full-power / afterburner audio provenance

Built by `tools/audio/tap_air_power.py` (2026-09-29) into `assets/bvp_audio/sounds/air/layer/`.

Sources from the Tyrants and Plebeians Flan's content pack (Technic modpack `tyrants-and-plebeians-mk1`,
`Flan/Tyrants and Plebeians Hero Shooter Update Final/assets/flansmod/sounds`), supplied by the project owner:
PlaneA, PlaneB, Mig15_engine, Mig21Engine, B1_EngineSound, Engine_Jet_AC130, 4745_Plane_MustangEngine,
4749_Plane_Yak9Engine, 4740_Plane_BF109Engine, RocketPassby (in flyby_jet). Original authorship and any existing licenses remain applicable.

Also mixed in: `air/layer/jet_roar` (Lizardpyle Battlefield 2 air sounds, see the r67 aircraft audio import).

Processing: mono, 44.1 kHz, loops made seamless by equal-power crossfade, tiled and mixed, RMS-levelled, soft-knee
peak limiting, pitch shifts by resampling. The afterburner tearing noise, crackle and ignition thump, the airflow loop and the flyby whooshes (apart from
the RocketPassby accent and the engine-roar surge taken from the power layers) are synthesized by the tool (fixed
random seeds).

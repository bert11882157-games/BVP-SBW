# TNT-equivalent table - BVP / SBW munitions

Generated 2026-09-25 from `munition_inventory.tsv` (1079 rows). Machine-readable version: `tnt_table.json`.

**Units:** kg of TNT equivalent. **Primary source:** War Thunder datamine ([gszabi99/War-Thunder-Datamine @ 2e2b2e0](https://github.com/gszabi99/War-Thunder-Datamine/tree/2e2b2e050d80802a64dc1e155d16e088cf2cf122), game 2.59.0.34). War Thunder rows are `explosiveMass x strengthEquivalent` from [`damage_model/explosive.blkx`](https://github.com/gszabi99/War-Thunder-Datamine/blob/2e2b2e050d80802a64dc1e155d16e088cf2cf122/aces.vromfs.bin_u/gamedata/damage_model/explosive.blkx), which is exactly the "TNT equivalent" War Thunder shows in game (3OF26: 3.402 kg A-IX-2 x 1.54 = 5.24 kg, same as the WT wiki).

Multipliers used (WT strengthEquivalent): TNT 1.0, A-IX-1 1.25, A-IX-2 1.54, Comp B 1.31, Comp A (A-3/A-5) 1.44, H-6 1.35, Tritonal 1.18, Hexal 1.7, OKFOL 1.62, Octol 1.59, RDX 1.6, RDX/TNT 1.28, LX-14 1.41, TGAF-5 1.6, TGAF-5M 1.6, TG-40 1.28, HTA 1.2, PETN 1.7, Tetryl 1.45, Torpex 1.6, H761 1.09, H10 1.7, PBXN-110 1.28. Non-WT fillers: C-4 1.34 (US Army FM 3-34.214), nitromethane 1.10, cyclotol ~1.4 (Wikipedia RE table).

Confidence: **H** = WT/official figure for that exact munition, **M** = close variant/proxy or secondary source, **L** = estimate.

## Tank / artillery / naval HE, HE-FS, HESH

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **155 mm PLZ-05 "Type 05" HE** <br><sub>`155mm_plz05_he`</sub> | 12.936 | 8.4 kg DHL-1 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/155mm_pl05_user_cannon.blkx :: name:155mm_plz_05_he` | H | 1 |
| **155 mm M107 HE** <br><sub>`m107`</sub> | 9.144 | 6.98 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/155mm_m185_user_cannon.blkx :: name:155mm_m107` | H | 4 |
| **120 mm L31A7 HESH** <br><sub>`l31a7`</sub> | 6.528 | 4.08 kg RDX x1.6 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_l30a1_user_cannon.blkx :: name:120mm_l37a7` | H | 1 |
| **125 mm DTB-125 HE-FS** <br><sub>`dtb125`</sub> | 5.464 | 3.548 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_type_99a_user_cannon.blkx :: name:125mm_dtb_125` | H | 3 |
| **YX-100 EM-gun HE shell (fictional)** <br><sub>`yx100_he`</sub> | 5.24 | - | estimate: proxy: 125 mm 3OF26 (WT datamine value) | L | 1 |
| **125 mm 3OF26 HE-FRAG** <br><sub>`3of26`</sub> | 5.239 | 3.402 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_2a46m_user_cannon.blkx :: name:125mm_3of_26` | H | 7 |
| **120 mm M356 HE (M58 gun)** <br><sub>`m356`</sub> | 4.651 | 3.55 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_m58_user_cannon.blkx :: name:120mm_m356` | H | 1 |
| **120 mm OE F1 HE-FS (Leclerc)** <br><sub>`f1_120_he`</sub> | 3.93 | 3 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_giat_cn120_26_f1_user_cannon.blkx :: name:120mm_f1_he` | H | 1 |
| **130 mm OF-42 HE (130/58 naval)** <br><sub>`130mm_of42`</sub> | 3.835 | 2.49 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/navalmodels_weapons/130mm_58_sm_2_1_naval_user_cannon.blkx :: name:130mm_naval_of_42` | M | 1 |
| **5"/54 Mk 41 HC (Mk 42 naval gun)** <br><sub>`5in54_mk41_hc`</sub> | 3.445 | 3.515 kg Explosive D x0.98 | wt_datamine: WT `weapons/navalmodels_weapons/127mm_54_mk18_turret_mk42_single_naval_user_cannon.blkx :: name:127mm_54_hc_mk41_mod0` | H | 1 |
| **138.6 mm OEA Mle 1928 HE (Mle 1934 naval gun)** <br><sub>`138mm_oea1928_he`</sub> | 3.41 | 3.1 kg Melinite x1.1 | wt_datamine: WT `weapons/navalmodels_weapons/138mm_50_model_1934_naval_user_cannon.blkx :: name:138mm_oea1928_he` | H | 1 |
| **100 mm 3OF32 HE-FRAG (2A70)** <br><sub>`3of32`</sub> | 2.603 | 1.69 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/100mm_2a70_user_cannon.blkx :: name:100mm_3of_32` | H | 2 |
| **100 mm OF-412 HE-FRAG** <br><sub>`of412`</sub> | 1.462 | 1.462 kg TNT x1.0 | wt_datamine: WT `weapons/groundmodels_weapons/100mm_d10t2s_user_cannon.blkx :: name:100mm_of_412` | H | 1 |
| **90 mm M71 HE** <br><sub>`m71`</sub> | 1.212 | 0.925 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/90mm_kanone_m41_user_cannon.blkx :: name:90mm_m71` | H | 1 |
| **90 mm OE 90 F1 HE** <br><sub>`oe90f1`</sub> | 0.945 | 0.945 kg TNT x1.0 | wt_datamine: WT `weapons/groundmodels_weapons/90mm_defa_d921_user_cannon.blkx :: name:90mm_oe_90_f1` | H | 1 |
| **73 mm OG-9 HE-FRAG (2A28 / SPG-9)** <br><sub>`og9`</sub> | 0.735 | 0.735 kg TNT x1.0 | wt_datamine: WT `weapons/groundmodels_weapons/73mm_2a28_user_cannon.blkx :: name:73mm_og_9` | H | 5 |
| **White-phosphorus shell (burster only)** <br><sub>`wp_shell`</sub> | 0.05 | 0.05 kg smoke/WP burster (WT smoke_composition) x1.0 | wt_datamine: WT `weapons/groundmodels_weapons/155mm_type_99_l52_user_cannon.blkx :: name:155mm_m110` | M | 4 |

## HEAT / HEAT-MP shells

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **152 mm M409A1 HEAT-MP** <br><sub>`m409a1`</sub> | 3.733 | 2.85 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/152mm_m81_user_cannon.blkx :: name:152mm_m409a1` | H | 1 |
| **125 mm 3BK18M HEAT-FS** <br><sub>`3bk18m`</sub> | 2.841 | 1.754 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_2a75_user_cannon.blkx :: name:125mm_3bk_18m` | H | 8 |
| **120 mm M469 HEAT-FS** <br><sub>`m469`</sub> | 2.672 | 2.04 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_m58_user_cannon.blkx :: name:120mm_m469` | H | 1 |
| **125 mm DTP-125 HEAT-FS** <br><sub>`dtp125`</sub> | 2.616 | 1.71 kg JH-2 x1.53 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_type_99a_user_cannon.blkx :: name:125mm_dtp_125` | H | 2 |
| **120 mm M830 HEAT-FS** <br><sub>`m830`</sub> | 2.362 | 1.64 kg Comp A (A-3/A-5) x1.44 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_m256_m1a2_user_cannon.blkx :: name:120mm_m830` | H | 1 |
| **120 mm DM12 HEAT-MP-T** <br><sub>`dm12_120`</sub> | 2.148 | 1.64 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_rheinmetall_l44_user_cannon.blkx :: name:120mm_dm12` | H | 1 |
| **120 mm DM12A1 HEAT-MP-T** <br><sub>`dm12a1`</sub> | 2.148 | 1.64 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_rheinmetall_l44_2pl_user_cannon.blkx :: name:120mm_dm12a1` | H | 2 |
| **120 mm OCC 120 G1 HEAT-FS** <br><sub>`occ120g1`</sub> | 2.148 | 1.64 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_giat_cn120_25_g1_user_cannon.blkx :: name:120mm_occ_120_g1` | H | 1 |
| **120 mm M830A1 MPAT (HEAT-MP)** <br><sub>`m830a1`</sub> | 1.391 | 0.966 kg Comp A (A-3/A-5) x1.44 | wt_datamine: WT `weapons/groundmodels_weapons/120mm_m256_m1a2_user_cannon.blkx :: name:120mm_m830a1` | H | 2 |
| **105 mm M456 HEAT-FS** <br><sub>`m456`</sub> | 1.271 | 0.97 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/105mm_m68_user_cannon.blkx :: name:105mm_m456` | H | 1 |
| **105 mm M456A2 HEAT-FS** <br><sub>`m456a2`</sub> | 1.271 | 0.97 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/105mm_m35_user_cannon.blkx :: name:105mm_m456a2` | H | 2 |
| **105 mm Type 83 HEAT-FS** <br><sub>`type83_heat`</sub> | 1.271 | 0.97 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/105mm_type_83_user_cannon.blkx :: name:105mm_TYPE83_HEAT` | H | 1 |
| **100 mm 3BK17M HEAT-FS** <br><sub>`3bk17m`</sub> | 1.169 | 0.935 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/groundmodels_weapons/100mm_d10t2s_user_cannon.blkx :: name:100mm_3bk_17m` | H | 1 |
| **90 mm OCC 90-62 HEAT-FS** <br><sub>`occ90_62`</sub> | 0.8777 | 0.67 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/90mm_gt_2_user_cannon.blkx :: name:90mm_occ_90_62` | H | 2 |
| **90 mm M431 HEAT-FS** <br><sub>`m431`</sub> | 0.7126 | 0.544 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/90mm_m41_user_cannon.blkx :: name:90mm_m431` | H | 1 |
| **73 mm PG-9 HEAT (2A28 / SPG-9)** <br><sub>`pg9`</sub> | 0.4025 | 0.322 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/groundmodels_weapons/73mm_2a28_user_cannon.blkx :: name:73mm_pg_9` | H | 10 |

## APHE / APCBC / SAP (filled AP)

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **155 mm BEA1-155 SAPCBC (PLZ-05)** <br><sub>`155mm_bea1_sap`</sub> | 7.854 | 5.1 kg JHL-3 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/155mm_pl05_user_cannon.blkx :: name:155mm_bea_1` | M | 1 |
| **138.6 mm OPfA Mle 1924 SAP** <br><sub>`138mm_opfa1924_sap`</sub> | 2.53 | 2.3 kg Melinite x1.1 | wt_datamine: WT `weapons/navalmodels_weapons/138mm_50_model_1934_naval_user_cannon.blkx :: name:138mm_opfa1924_sap` | M | 1 |
| **130 mm PB-42 SAP (130/58 naval)** <br><sub>`130mm_pb42_sap`</sub> | 2.205 | 1.432 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/navalmodels_weapons/130mm_58_sm_2_1_naval_user_cannon.blkx :: name:130mm_naval_pb_42` | M | 1 |
| **5"/54 Mk 42 Mod 0 Special Common (SAP)** <br><sub>`5in54_mk42_common`</sub> | 0.97 | 0.97 kg TNT x1.0 | wt_datamine: WT `weapons/navalmodels_weapons/127mm_54_mk18_turret_mk42_single_naval_user_cannon.blkx :: name:127mm_54_mk42_mod0_common` | M | 1 |
| **90 mm M82 APCBC (APHE)** <br><sub>`m82_90`</sub> | 0.1372 | 0.14 kg Explosive D x0.98 | wt_datamine: WT `weapons/groundmodels_weapons/90mm_kanone_m41_user_cannon.blkx :: name:90mm_m82` | H | 1 |
| **88 mm PzGr 39 APCBC-HE (KwK 36)** <br><sub>`pzgr39_88`</sub> | 0.1088 | 0.064 kg H10 x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/88mm_kwk36_user_cannon.blkx :: name:88mm_pzrg_39` | H | 2 |
| **88 mm PzGr 39/43 APCBC-HE (KwK 43)** <br><sub>`pzgr39_43`</sub> | 0.1088 | 0.064 kg H10 x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/88mm_kwk43_user_cannon.blkx :: name:88mm_pzrg_39_43` | H | 2 |
| **100 mm BR-412D APCBC (APHE)** <br><sub>`br412d`</sub> | 0.1001 | 0.065 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/100mm_d10t2s_user_cannon.blkx :: name:100mm_br_412d` | H | 1 |
| **75 mm PzGr 39 APCBC-HE (KwK 40)** <br><sub>`pzgr39`</sub> | 0.0289 | 0.017 kg H10 x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/75mm_kwk40_l48_user_cannon.blkx :: name:75mm_pzgr_39` | H | 2 |
| **75 mm PzGr 39/42 APCBC-HE (KwK 42)** <br><sub>`pzgr39_42`</sub> | 0.0289 | 0.017 kg H10 x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/75mm_kwk42_user_cannon.blkx :: name:75mm_pzrg_39_42` | H | 2 |

## Kinetic (no filler)

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **.303 British ball / AP / incendiary belt** <br><sub>`k_303_ball`</sub> | 0 | - | wt_datamine: WT `weapons/gunbrowning303.blkx` | H | 8 |
| **100 mm 3BM25 APFSDS** <br><sub>`k_3bm25`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 100mm_3bm25 carries no explosiveMass in any weapon file) | H | 1 |
| **105 mm M728 APDS** <br><sub>`k_m728`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 105mm_m728 carries no explosiveMass in any weapon file) | H | 1 |
| **105 mm M774 APFSDS** <br><sub>`k_m774`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 105mm_m774 carries no explosiveMass in any weapon file) | H | 1 |
| **105 mm M900 APFSDS** <br><sub>`k_m900`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 105mm_m900 carries no explosiveMass in any weapon file) | H | 1 |
| **105 mm OFL 105 F3 APFSDS** <br><sub>`k_ofl105f3`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 105mm_ofl_f3 carries no explosiveMass in any weapon file) | H | 2 |
| **115 mm 3BM4 APFSDS** <br><sub>`k_3bm4`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 115mm_3bm4 carries no explosiveMass in any weapon file) | H | 2 |
| **12.7x99 (.50 BMG) AP / API / incendiary belt** <br><sub>`k_12_7x99_api`</sub> | 0 | - | wt_datamine: WT `weapons/gunbrowning50_m3.blkx ; https://github.com/gszabi99/War-Thunder-Datamine/blob/` | H | 36 |
| **120 mm DM53 APFSDS** <br><sub>`k_dm53`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 120mm_dm53 carries no explosiveMass in any weapon file) | H | 2 |
| **120 mm M829A2 APFSDS** <br><sub>`k_m829a2`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 120mm_m829a2 carries no explosiveMass in any weapon file) | H | 2 |
| **125 mm 3BM22 APFSDS** <br><sub>`k_3bm22`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 125mm_3bm22 carries no explosiveMass in any weapon file) | H | 1 |
| **125 mm 3BM42 APFSDS** <br><sub>`k_3bm42`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 125mm_3bm42 carries no explosiveMass in any weapon file) | H | 8 |
| **125 mm 3BM60 APFSDS** <br><sub>`k_3bm60`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 125mm_3bm60 carries no explosiveMass in any weapon file) | H | 4 |
| **125 mm DTC10-125 APFSDS** <br><sub>`k_dtc10_125`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 125mm_dtc10_125 carries no explosiveMass in any weapon file) | H | 2 |
| **14.5 mm BZT/B-32 API(-T)** <br><sub>`k_14_5_api_t`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 14mm_apit carries no explosiveMass in any weapon file) | H | 1 |
| **152 mm XM578E1 APFSDS (M551 "APFSDS")** <br><sub>`k_xm578e1`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 152mm_xm578e1 carries no explosiveMass in any weapon file) | H | 2 |
| **20 mm DM63 APDS** <br><sub>`k_dm63_20mm`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 20mm_dm63 carries no explosiveMass in any weapon file) | H | 2 |
| **20 mm M601 APCR-T (HS.820/M139)** <br><sub>`k_20mm_m601`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 20mm_m601 carries no explosiveMass in any weapon file) | H | 1 |
| **23 mm BZT API-T** <br><sub>`k_23mm_apit`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 23mm_apit carries no explosiveMass in any weapon file) | H | 2 |
| **25 mm M791 APDS-T** <br><sub>`k_m791`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 25mm_m791 carries no explosiveMass in any weapon file) | H | 3 |
| **25 mm M811 PMB090 APFSDS** <br><sub>`k_pmb090`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 25mm_pmb_09 carries no explosiveMass in any weapon file) | H | 1 |
| **25 mm M919 APFSDS-T** <br><sub>`k_m919`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 25mm_m919 carries no explosiveMass in any weapon file) | H | 1 |
| **30 mm 3UBR6 AP-T** <br><sub>`k_3ubr6`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 30mm_UBR6 carries no explosiveMass in any weapon file) | H | 3 |
| **30 mm 3UBR8 APDS** <br><sub>`k_3ubr8`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 30mm_3UBR8 carries no explosiveMass in any weapon file) | H | 5 |
| **30 mm APDS (H/PJ-11 CIWS)** <br><sub>`k_30mm_apds_hpj11`</sub> | 0 | - | estimate: kinetic penetrator: no explosive filler | H | 1 |
| **40 mm Slpprj m/01 APFSDS** <br><sub>`k_slpprj_m01`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 40mm_slpprj_m01 carries no explosiveMass in any weapon file) | H | 1 |
| **76 mm M331A2 APDS** <br><sub>`k_m331a2`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 76mm_m331a2 carries no explosiveMass in any weapon file) | H | 2 |
| **90 mm M332 APCR/HVAP** <br><sub>`k_m332`</sub> | 0 | - | wt_datamine: kinetic penetrator: no explosive filler (War Thunder bulletName 90mm_m332 carries no explosiveMass in any weapon file) | H | 1 |
| **YX-100 electromagnetic-gun slug (fictional)** <br><sub>`k_yx100_railgun`</sub> | 0 | - | estimate: kinetic penetrator: no explosive filler | H | 1 |

## Autocannon

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **57 mm Bofors HE-T** <br><sub>`57mm_bofors_he`</sub> | 0.765 | 0.45 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/57mm_bofors_l70_mk_1_user_cannon.blkx :: name:57_438mm_he` | H | 1 |
| **57 mm Bofors HE-VT (proximity)** <br><sub>`57mm_bofors_he_vt`</sub> | 0.765 | 0.45 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/57mm_bofors_l70_mk_1_user_cannon.blkx :: name:57_438mm_he_vt` | H | 1 |
| **57 mm Bofors SAPHEI** <br><sub>`57mm_bofors_sap`</sub> | 0.51 | 0.3 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/57mm_bofors_l70_mk_1_user_cannon.blkx :: name:57_438mm_sap` | H | 1 |
| **50 mm MK 214A M-Geschoss HE-I-T** <br><sub>`50mm_mk214_mine`</sub> | 0.42 | 0.35 kg HTA x1.2 | wt_datamine: WT `weapons/cannonmk214a.blkx :: bullet/2` | H | 1 |
| **50 mm MK 214A HE-FRAG-I** <br><sub>`50mm_mk214_he`</sub> | 0.3 | 0.25 kg HTA x1.2 | wt_datamine: WT `weapons/cannonmk214a.blkx :: bullet/1` | H | 1 |
| **35x228 mm HEI-T (Oerlikon KDA/KDE, DM11A1-class)** <br><sub>`35x228_hei_t`</sub> | 0.204 | 0.12 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/35mm_oerlikon_kda_user_cannon.blkx :: name:35_228mm_he_i_t` | H | 3 |
| **40x364 mm Slsgr m/90 HE (CV9040)** <br><sub>`40mm_slsgr_m90`</sub> | 0.1744 | 0.109 kg Torpex x1.6 | wt_datamine: WT `weapons/groundmodels_weapons/40mm_kan_strf_user_cannon.blkx :: name:40mm_slsgr_m90` | H | 2 |
| **30x173 mm PGU-13/B HEI (GAU-8)** <br><sub>`30x173_pgu13`</sub> | 0.0928 | 0.058 kg RDX x1.6 | wt_datamine: WT `weapons/cannongau_8a.blkx :: bullet/1` | H | 2 |
| **30x90 mm MK 108 M-Geschoss HE-I-T** <br><sub>`30x90_mk108_mine`</sub> | 0.09 | 0.075 kg HTA/PETN x1.2 | wt_datamine: WT `weapons/cannonmk108.blkx :: bullet/0` | H | 12 |
| **30x111 mm ADEN/akan m/55 HE-I** <br><sub>`30x111_akan55_hei`</sub> | 0.0896 | 0.056 kg Torpex x1.6 | wt_datamine: WT `weapons/cannonakan_m55.blkx :: bullet/2` | H | 12 |
| **30x173 mm Oerlikon KCA HE-I-T (akan m/75)** <br><sub>`30x173_kca_hei`</sub> | 0.0896 | 0.056 kg Torpex x1.6 | wt_datamine: WT `weapons/cannonoerlikon_buhrle_kca.blkx :: bullet/1` | H | 2 |
| **30x184 mm MK 103 M-Geschoss HE-I-T** <br><sub>`30x184_mk103_mine`</sub> | 0.0864 | 0.072 kg HTA/PETN x1.2 | wt_datamine: WT `weapons/cannonmk103.blkx :: bullet/0` | H | 10 |
| **30x113 mm DEFA 552/553 HE-I** <br><sub>`30x113_defa_hei`</sub> | 0.085 | 0.05 kg Hexal x1.7 | wt_datamine: WT `weapons/cannondefa552.blkx :: bullet/1` | H | 12 |
| **30x170 mm L13 HEI-T (RARDEN)** <br><sub>`30x170_rarden_hei_t`</sub> | 0.085 | 0.05 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/30mm_l21a1_rarden_user_cannon.blkx :: name:30_170mm_he_i_t` | H | 2 |
| **30x165 mm 3UOF8 HE-I (2A42/2A72)** <br><sub>`30x165_3uof8`</sub> | 0.0755 | 0.049 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/30mm_2a42_user_cannon.blkx :: name:30mm_UOF8` | H | 11 |
| **30x165 mm Chinese HEI (ZPT-99 / "DTY02")** <br><sub>`30x165_zpt99_hei`</sub> | 0.0755 | 0.049 kg JHL-3 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/30mm_zpt_99_user_cannon.blkx :: name:30mm_zpz02_hei` | H | 2 |
| **30x165 mm OFZ HE-I (GSh-30-1 / GSh-30-2)** <br><sub>`30x165_ofz_gsh30`</sub> | 0.0747 | 0.0485 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannongsh_301.blkx :: bullet/1` | H | 14 |
| **30x155 mm NR-30 OFZ HE-I(-T)** <br><sub>`30x155_nr30_ofz`</sub> | 0.0671 | 0.0436 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannonnr30.blkx :: bullet/0` | H | 16 |
| **37 mm M4 HE-I-T (P-39)** <br><sub>`37x145_m54_he`</sub> | 0.0658 | 0.04536 kg Tetryl x1.45 | wt_datamine: WT `weapons/cannonm4.blkx :: bullet/0` | H | 2 |
| **37x155 mm N-37 OFZ HE-I-T** <br><sub>`37x155_n37_ofz`</sub> | 0.057 | 0.037 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannonn37d.blkx :: bullet/0` | H | 8 |
| **25x137 mm M792 HEI-T** <br><sub>`25x137_m792`</sub> | 0.0544 | 0.032 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/25mm_m242_user_cannon.blkx :: name:25mm_m792` | H | 10 |
| **25x137 mm PGU-25/U HEI (GAU-12)** <br><sub>`25x137_pgu25`</sub> | 0.0544 | 0.032 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/25mm_gau_12u_user_cannon.blkx :: name:25mm_pgu_25_u` | H | 1 |
| **30x113 mm DEFA 552/553 HE-FRAG** <br><sub>`30x113_defa_hef`</sub> | 0.0476 | 0.028 kg Hexal x1.7 | wt_datamine: WT `weapons/cannondefa552.blkx :: bullet/2` | H | 4 |
| **30x150 mm GIAT 30M791 SAPHEI (Rafale)** <br><sub>`30x150_m791_saphei`</sub> | 0.0384 | 0.03 kg RDX/Al x1.28 | wt_datamine: WT `weapons/cannonglat_m791.blkx :: bullet` | H | 2 |
| **30x170 mm APSE/APHE-I (RARDEN)** <br><sub>`30x170_rarden_aphe`</sub> | 0.0306 | 0.018 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/30mm_l21a1_rarden_user_cannon.blkx :: name:30_170mm_aphe_i` | H | 4 |
| **20x82 mm MG 151/20 M-Geschoss HE-I** <br><sub>`20x82_mg151_mine`</sub> | 0.0298 | 0.0186 kg HA 41 x1.6 | wt_datamine: WT `weapons/cannonmg15120.blkx :: bullet/3` | H | 2 |
| **25 mm Type 87 FRAG-I-T (ZPT-90)** <br><sub>`25mm_type87_he`</sub> | 0.0293 | 0.019 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/25mm_zpt90_user_cannon.blkx :: name:25mm_type_87_he` | H | 1 |
| **75 mm BK 7,5 APCBC-HE (PzGr 39)** <br><sub>`bk75_aphe`</sub> | 0.0289 | 0.017 kg H10 x1.7 | wt_datamine: WT `weapons/cannonbk75.blkx :: bullet` | H | 2 |
| **23x152 mm OFZ HE-I (ZU-23 / ZSU-23-4)** <br><sub>`23x152_ofz`</sub> | 0.0285 | 0.0185 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/23mm_2a14_user_cannon.blkx :: name:23mm_hei` | H | 4 |
| **27x145 mm BK 27 HE-FRAG** <br><sub>`27x145_bk27_he`</sub> | 0.0282 | 0.022 kg RDX/TNT x1.28 | wt_datamine: WT `weapons/cannon_mauser_bk_27.blkx :: bk_27_air_targets/bullet/2` | H | 8 |
| **30x113 mm M789 HEDP (M230)** <br><sub>`30x113_m789_hedp`</sub> | 0.0282 | 0.02203 kg PBXN-5 x1.28 | wt_datamine: WT `weapons/cannonm230e1.blkx :: bullet` | H | 3 |
| **23x115 mm OFZ HE-I (GSh-23L)** <br><sub>`23x115_ofz_gsh23`</sub> | 0.0279 | 0.0181 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannongsh_23l.blkx :: bullet/2` | H | 29 |
| **20x128 mm M594 HEI-T (HS.820 / M139)** <br><sub>`20x128_m594`</sub> | 0.0272 | 0.016 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/20mm_m139_user_cannon.blkx :: name:20mm_m594` | M | 1 |
| **30x155 mm NR-30 AP-HE** <br><sub>`30x155_nr30_aphe`</sub> | 0.0225 | 0.0146 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannonnr30.blkx :: bullet/3` | H | 6 |
| **23x152 mm OFZT HE-I-T** <br><sub>`23x152_ofzt`</sub> | 0.02 | 0.013 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/23mm_2a14_user_cannon.blkx :: name:23mm_heit` | H | 1 |
| **20x110 mm Mk 12 HEI (Colt Mk 12)** <br><sub>`20x110_mk12_hei`</sub> | 0.0183 | 0.014 kg Comp B x1.31 | wt_datamine: WT `weapons/cannonbrowning-colt_mk12_mod0.blkx :: bullet/1` | H | 8 |
| **30x165 mm 3UOR6 FRAG-T** <br><sub>`30x165_3uor6`</sub> | 0.0179 | 0.0116 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/30mm_2a42_user_cannon.blkx :: name:30mm_UOR6` | H | 13 |
| **23x115 mm OZT FRAG-I-T (NS-23/NR-23/AM-23)** <br><sub>`23x115_ozt`</sub> | 0.0169 | 0.011 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannonns23.blkx :: bullet/1` | H | 30 |
| **20x102 mm M56 HEI (M61/M39/M197/M195/GAU-4)** <br><sub>`20x102_m56_hei`</sub> | 0.0117 | 0.0107 kg H761 x1.09 | wt_datamine: WT `weapons/cannonm61a1.blkx :: bullet/0` | H | 39 |
| **20x110 mm AN/M3 HEI** <br><sub>`20x110_anm3_hei`</sub> | 0.0113 | 0.00776 kg Tetryl x1.45 | wt_datamine: WT `weapons/cannonan_m3.blkx :: bullet/0` | H | 16 |
| **20x139 mm DM51A1 HEI-T (Rh 202)** <br><sub>`20x139_dm51a1`</sub> | 0.011 | 0.0065 kg Hexal x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/20mm_rh_202_marder_user_cannon.blkx :: name:20mm_dm51a1` | H | 8 |
| **20 mm akan m/41A & m/47C HE-T** <br><sub>`20mm_akan_he_t`</sub> | 0.0102 | 0.006 kg PETN x1.7 | wt_datamine: WT `weapons/cannonakan_m41a.blkx :: bullet/0` | H | 10 |
| **20x110 mm Hispano HE-I (Mk II / M50)** <br><sub>`20x110_hispano_hei`</sub> | 0.0093 | 0.0064 kg Tetryl x1.45 | wt_datamine: WT `weapons/cannonhispanomkii.blkx :: bullet/0` | H | 20 |
| **30x184 mm MK 103 SAPHEI-T** <br><sub>`30x184_mk103_saphei`</sub> | 0.0088 | 0.0052 kg PETN x1.7 | wt_datamine: WT `weapons/cannonmk103.blkx :: bullet/1` | H | 5 |
| **20x110 mm M24A1 HEI (B-47 tail)** <br><sub>`20x110_m24a1_hei`</sub> | 0.008 | 0.0055 kg Tetryl x1.45 | wt_datamine: WT `weapons/cannonm24a1.blkx :: bullet/1` | H | 4 |
| **20x82 mm MG 151/20 AP-HE** <br><sub>`20x82_mg151_aphe`</sub> | 0.0078 | 0.00459 kg PETN x1.7 | wt_datamine: WT `weapons/cannonmg15120.blkx :: bullet/2` | H | 2 |
| **20x99R mm ShVAK/B-20 FI-T** <br><sub>`20x99_shvak_fit`</sub> | 0.0064 | 0.00413 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/cannonshvak.blkx :: bullet/0` | H | 6 |
| **20x99R mm B-20 HE-FRAG** <br><sub>`20x99_b20_he`</sub> | 0.0058 | 0.004 kg Tetryl x1.45 | wt_datamine: WT `weapons/cannonbt20_turret.blkx :: shvak_turret_het/bullet/1` | H | 1 |

## Heavy machine-gun HE-I

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **13.2 mm akan m/39A HE-T** <br><sub>`13_2mm_akan_m39a`</sub> | 0.0035 | 0.0035 kg TNT x1.0 | wt_datamine: WT `weapons/gunakan_m39a.blkx :: bullet/0` | H | 8 |
| **14.5x114 mm MDZ HE-I (KPVT)** <br><sub>`14_5x114_mdz`</sub> | 0.0034 | 0.002 kg PETN x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/14_5mm_kpvt_user_machinegun.blkx :: name:14mm_mdz` | H | 1 |
| **12.7x108 mm MDZ HE-I (Kord/NSV/YakB/UB/A-12.7/QJC-88)** <br><sub>`12_7x108_mdz`</sub> | 0.0032 | 0.0019 kg PETN x1.7 | wt_datamine: WT `weapons/groundmodels_weapons/12_7mm_6p50_kord_user_machinegun.blkx :: name:i_ball_mdz` | H | 27 |

## Grenades, grenade-launcher and mortar

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **82 mm O-832 HE mortar bomb** <br><sub>`mortar_82mm`</sub> | 0.4 | 0.4 kg TNT x1.0 | wt_datamine: WT `weapons/bombguns/bomb_ussr_82mm_o_832.blkx :: bomb` | M | 4 |
| **M67 fragmentation hand grenade** <br><sub>`m67`</sub> | 0.2358 | 0.18 kg Comp B x1.31 | wiki: https://en.wikipedia.org/wiki/M67_grenade (180 g Composition B); multiplier = WT comp_b strengthEquivalent | H | 1 |
| **RGO defensive hand grenade** <br><sub>`rgo`</sub> | 0.1125 | 0.09 kg A-IX-1 x1.25 | wiki: https://en.wikipedia.org/wiki/RGO_hand_grenade (90 g A-IX-1); multiplier = WT a_ix_1 strengthEquivalent | H | 2 |
| **30 mm VOG-30 HE-FRAG (AGS-17/30)** <br><sub>`vog30`</sub> | 0.0647 | 0.042 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/30mm_ag_17_user_cannon.blkx :: name:30mm_vog_30` | H | 6 |
| **40 mm LV grenade (M433 HEDP / M406 HE class)** <br><sub>`40mm_grenade`</sub> | 0.041 | 0.032 kg RDX/TNT x1.28 | wt_datamine: WT `weapons/human_weapons/grenades/m433_grenade.blkx` | M | 3 |

## Demolition charges

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **C-4 block (M112, 1.25 lb)** <br><sub>`c4_m112`</sub> | 0.7598 | 0.567 kg C-4 x1.34 | official: https://www.globalsecurity.org/military/systems/munitions/m112-c4.htm (1.25 lb C-4, RE 1.34 per FM 3-34.214) | H | 2 |

## Mines

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **TM-62M anti-tank mine** <br><sub>`tm62m`</sub> | 7.5 | 7.5 kg TNT x1.0 | wiki: https://en.wikipedia.org/wiki/TM-62_mine (7.5 kg charge, TNT for TM-62M) | H | 2 |
| **Type 3 lunge mine** <br><sub>`lunge_mine`</sub> | 3 | 3 kg TNT x1.0 | wiki: https://en.wikipedia.org/wiki/Lunge_mine (3.0 kg crude TNT) | H | 1 |
| **PTKM-1R top-attack EFP submunition** <br><sub>`ptkm1r_efp`</sub> | 2.8 | 2.8 kg unknown (assumed TNT-eq) x1.0 | wiki: https://en.wikipedia.org/wiki/PTKM-1R (filling weight 2.8 kg) | L | 1 |
| **M18A1 Claymore** <br><sub>`m18a1`</sub> | 0.9112 | 0.68 kg C-4 x1.34 | wiki: https://en.wikipedia.org/wiki/Claymore_mine (680 g C-4); RE 1.34 (FM 3-34.214) | H | 1 |
| **Entry Denial Device (fictional, Rainbow Six-style trip charge)** <br><sub>`edd`</sub> | 0.25 | - | estimate: estimate | L | 1 |
| **BLU-43/B Dragontooth** <br><sub>`blu43`</sub> | 0.0099 | 0.009 kg nitromethane/nitroethane x1.1 | wiki: https://en.wikipedia.org/wiki/BLU-43_Dragontooth (9 g nitromethane/nitroethane); RE 1.10 (nitromethane, Wikipedia TNT equivalent table) | M | 1 |

## Unguided rockets

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **122 mm 9M22 (M-21OF) Grad HE-FRAG** <br><sub>`9m22_grad`</sub> | 9.856 | 6.4 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/navalmodels_weapons/rocket_bm21_launcher.blkx :: rocket` | H | 2 |
| **127 mm Zuni Mk 32 HEAT/APERS** <br><sub>`zuni_mk32`</sub> | 8.913 | 6.804 kg Comp B x1.31 | wt_datamine: WT `weapons/rocketguns/us_zuni_wafar_mk32.blkx :: rocket` | H | 3 |
| **TBG-7V thermobaric (RPG-7)** <br><sub>`tbg7v`</sub> | 2.783 | 2.15 kg thermobaric OM 100MI-3L + A-IX-1 | wiki: https://en.wikipedia.org/wiki/RPG-7 (TBG-7V: 1.9 kg OM 100MI-3L thermobaric mix + 0.25 kg A-IX-1) | L | 3 |
| **122 mm S-13 (basic, penetrating)** <br><sub>`s13`</sub> | 2.772 | 1.8 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/rocketguns/su_s_13_rocket.blkx :: rocket` | M | 3 |
| **80 mm S-8KO HEAT-FRAG** <br><sub>`s8ko`</sub> | 1.375 | 1.1 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/rocketguns/su_s_8ko_rocket.blkx :: rocket` | H | 28 |
| **70 mm Hydra/FFAR M151 HE-FRAG** <br><sub>`hydra70_m151`</sub> | 1.367 | 1.043 kg Comp B x1.31 | wt_datamine: WT `weapons/rocketguns/us_2_75_in_ffar_mighty_mouse_m151.blkx :: rocket` | H | 3 |
| **107 mm Type 63 HE-FRAG rocket** <br><sub>`type63_107_he`</sub> | 1.3 | 1.3 kg TNT (assumed) x1.0 | wiki: https://en.wikipedia.org/wiki/Type_63_multiple_rocket_launcher ("18.8 kg rocket with a 1.3 kg warhead") | M | 3 |
| **70 mm Hydra M247 HEAT/DP** <br><sub>`hydra70_m247`</sub> | 1.192 | 0.91 kg Comp B x1.31 | wt_datamine: WT `weapons/rocketguns/us_hydra_70_m247.blkx :: rocket` | H | 4 |
| **57 mm S-5K HEAT** <br><sub>`s5k`</sub> | 0.465 | 0.372 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/rocketguns/su_s_5k_rocket.blkx :: rocket` | H | 2 |
| **PG-7VM HEAT (RPG-7)** <br><sub>`pg7vm`</sub> | 0.4 | 0.32 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/human_weapons/bullets/rpg_7_pg_7vm_rocket.blkx :: rocket` | H | 3 |
| **107 mm "AP" rocket (SBW medium rocket AP)** <br><sub>`type63_107_ap`</sub> | 0 | - | estimate: no real 107 mm AP rocket | L | 3 |

## ATGMs

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **9M127 Vikhr (9K121/9K127)** <br><sub>`9m127_vikhr`</sub> | 6.871 | 4.3 kg OKFOL-3.5 x1.598 | wt_datamine: WT `weapons/rocketguns/su_9m127.blkx :: rocket` | H | 4 |
| **9M133 Kornet** <br><sub>`9m133`</sub> | 6.48 | 4 kg OKFOL x1.62 | wt_datamine: WT `weapons/rocketguns/su_9m133_kornet.blkx :: rocket` | H | 4 |
| **HOT 3** <br><sub>`hot3`</sub> | 6.455 | 4.06 kg Octol x1.59 | wt_datamine: WT `weapons/rocketguns/euro_hot3.blkx :: rocket` | H | 2 |
| **9M112 Kobra (125 mm gun-launched)** <br><sub>`9m112`</sub> | 5.832 | 3.6 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_2a46_2_user_cannon.blkx :: name:125mm_9m_112>rocket` | H | 2 |
| **9M119M1 Invar-M (125 mm gun-launched)** <br><sub>`9m119m1`</sub> | 5.832 | 3.6 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_2a46_m5_t_90a_user_cannon.blkx :: name:125mm_9m_119m1>rocket` | H | 4 |
| **GP125 (125 mm gun-launched)** <br><sub>`gp125`</sub> | 5.832 | 3.6 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_type_99a_user_cannon.blkx :: name:125mm_GP125>rocket` | H | 2 |
| **MGM-51C Shillelagh** <br><sub>`mgm51c`</sub> | 5.724 | 3.6 kg Octol x1.59 | wt_datamine: WT `weapons/groundmodels_weapons/152mm_xm150e5_user_cannon.blkx :: name:152mm_mgm_61c>rocket` | H | 1 |
| **FGM-148 Javelin** <br><sub>`javelin`</sub> | 5.64 | 4 kg LX-14 class (assumed) x1.41 | estimate: https://en.wikipedia.org/wiki/FGM-148_Javelin (8.4 kg tandem warhead); explosive fraction ~48% assumed | L | 1 |
| **Type 79 Jyu-MAT (HEAT)** <br><sub>`type79`</sub> | 5.502 | 4.2 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/152mm_type_79_launcher_user_cannon.blkx :: name:type_79_atgm>rocket` | H | 2 |
| **9M117 Bastion (100 mm gun-launched)** <br><sub>`9m117`</sub> | 4.86 | 3 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/100mm_2a70_user_cannon.blkx :: name:100mm_9m_117>rocket` | H | 2 |
| **GP105 (105 mm gun-launched)** <br><sub>`gp105`</sub> | 4.86 | 3 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/105mm_zpl98a_user_cannon.blkx :: name:105mm_gp_105>rocket` | H | 1 |
| **BGM-71E TOW-2A** <br><sub>`bgm71e`</sub> | 4.836 | 3.43 kg LX-14 x1.41 | estimate: WT `weapons/rocketguns/us_127mm_tow2.blkx :: rocket (TOW-2 main charge 3.13 kg LX-14) + ~0.3 kg precursor (assumed)` | M | 1 |
| **9M120 Ataka** <br><sub>`9m120`</sub> | 4.568 | 2.82 kg OKFOL x1.62 | wt_datamine: WT `weapons/rocketguns/su_9m120.blkx :: rocket` | H | 7 |
| **9M113 Konkurs** <br><sub>`9m113`</sub> | 4.455 | 2.75 kg OKFOL x1.62 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_9m113_rocket_launcher.blkx :: rocket` | H | 5 |
| **9M17M Falanga** <br><sub>`9m17m`</sub> | 4.32 | 3.6 kg HTA x1.2 | wt_datamine: WT `weapons/rocketguns/su_9m17m.blkx :: rocket` | H | 2 |
| **9M114 Shturm** <br><sub>`9m114`</sub> | 4.001 | 2.47 kg OKFOL x1.62 | wt_datamine: WT `weapons/rocketguns/su_9m114.blkx :: rocket` | H | 4 |
| **BGM-71A TOW** <br><sub>`bgm71a`</sub> | 3.752 | 2.36 kg Octol x1.59 | wt_datamine: WT `weapons/groundmodels_weapons/127mm_tow_rocket_launcher.blkx :: name:127mm_atgm_tow>rocket` | H | 12 |
| **9M14(M) Malyutka** <br><sub>`9m14`</sub> | 3.25 | 2.6 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_9m14_rocket_launcher.blkx :: rocket` | H | 4 |
| **MILAN 3 (tandem)** <br><sub>`milan3`</sub> | 2.62 | 2 kg Comp B x1.31 | estimate: WT `weapons/groundmodels_weapons/115mm_milan_2_rocket_launcher.blkx :: rocket (MILAN 2 main charge 1.8 kg Comp B) + ~0.2 kg precursor (assumed)` | M | 1 |
| **HJ-73E (tandem)** <br><sub>`hj73e`</sub> | 1.834 | 1.4 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/125mm_hj_73_rocket_launcher_user_cannon.blkx :: name:125mm_hj_73e>rocket` | H | 2 |
| **MILAN 1 (103 mm)** <br><sub>`milan1`</sub> | 1.834 | 1.4 kg Comp B x1.31 | wt_datamine: WT `weapons/groundmodels_weapons/103mm_milan_rocket_launcher.blkx :: rocket` | H | 4 |
| **QN502CDD loitering ATGM** <br><sub>`qn502cdd`</sub> | 1.386 | 0.9 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/151mm_qn502cdd_rocket_launcher.blkx :: name:151mm_QN502CDD>rocket` | H | 2 |
| **QN201DD micro-missile** <br><sub>`qn201dd`</sub> | 0.75 | 0.6 kg A-IX-1 x1.25 | wt_datamine: WT `weapons/groundmodels_weapons/70mm_qn201dd_rocket_launcher.blkx :: name:70mm_QN201DD>rocket` | H | 2 |

## SAMs / MANPADS

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **9M311 (2S6 Tunguska SAM)** <br><sub>`9m311`</sub> | 4.62 | 3 kg A-IX-2 x1.54 | wt_datamine: WT `weapons/groundmodels_weapons/152mm_9m311_rocket_launcher.blkx :: bullet/rocket` | H | 2 |
| **9M336 Verba** <br><sub>`9m336`</sub> | 0.9234 | 0.57 kg OKFOL x1.62 | wt_datamine: WT `weapons/rocketguns/su_9m336.blkx :: rocket` | H | 1 |
| **FIM-92 Stinger** <br><sub>`fim92`</sub> | 0.54 | 0.45 kg HTA x1.2 | wt_datamine: WT `weapons/rocketguns/us_fim-92b.blkx :: rocket` | H | 1 |
| **9M39 Igla (9K38)** <br><sub>`9m39_igla`</sub> | 0.53 | 0.4 kg OKFOL-20 x1.325 | wt_datamine: WT `weapons/rocketguns/su_9m39.blkx :: rocket` | H | 3 |

## Air-to-air missiles

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **AIM-174B (SM-6 derived)** <br><sub>`aim174b`</sub> | 36.864 | 28.8 kg PBXN-110 class (assumed) x1.28 | estimate: https://en.wikipedia.org/wiki/AIM-174B (64 kg blast-frag warhead) | L | 1 |
| **AIM-54C+ Phoenix** <br><sub>`aim54c_plus`</sub> | 33.391 | 31.801 kg PBXN-104 x1.05 | wt_datamine: WT `weapons/rocketguns/us_aim_54c_plus.blkx :: rocket` | H | 1 |
| **AIM-54A Phoenix** <br><sub>`aim54a`</sub> | 27.624 | 26.308 kg PBXN-104 x1.05 | wt_datamine: WT `weapons/rocketguns/us_aim_54a.blkx :: rocket` | H | 1 |
| **R-27ET** <br><sub>`r27et`</sub> | 24 | 15 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_27et.blkx :: rocket` | H | 1 |
| **R-27R** <br><sub>`r27r`</sub> | 24 | 15 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_27r.blkx :: rocket` | H | 2 |
| **R-27T** <br><sub>`r27t`</sub> | 24 | 15 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_27t.blkx :: rocket` | H | 2 |
| **R-77** <br><sub>`r77`</sub> | 15.56 | 9.725 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_77.blkx :: rocket` | H | 4 |
| **AIM-7E Sparrow** <br><sub>`aim7e`</sub> | 9.525 | 9.072 kg PBXN-104 x1.05 | wt_datamine: WT `weapons/rocketguns/us_aim7e_sparrow.blkx :: rocket` | H | 1 |
| **Rb 71 Sky Flash** <br><sub>`rb71`</sub> | 9.525 | 9.072 kg PBXN-104 x1.05 | wt_datamine: WT `weapons/rocketguns/swd_rb71.blkx :: rocket` | H | 1 |
| **MBDA Meteor** <br><sub>`meteor`</sub> | 9.178 | 7.17 kg PBXN-110 class (assumed) x1.28 | estimate: https://en.wikipedia.org/wiki/Meteor_(missile) (blast-frag, mass unpublished); proxy AIM-120C (WT) | L | 1 |
| **AIM-120C-5/C-7 AMRAAM** <br><sub>`aim120c`</sub> | 9.174 | 7.167 kg PBXN-110 x1.28 | wt_datamine: WT `weapons/rocketguns/us_aim_120c_7.blkx :: rocket` | H | 2 |
| **R-13M** <br><sub>`r13m`</sub> | 8.8 | 5.5 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_13m.blkx :: rocket` | H | 1 |
| **R-13M1** <br><sub>`r13m1`</sub> | 8.8 | 5.5 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_13m1.blkx :: rocket` | H | 1 |
| **R-3R** <br><sub>`r3r`</sub> | 8.8 | 5.5 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/rocketguns/su_r_3r.blkx :: rocket` | H | 1 |
| **AIM-9B Sidewinder (incl. FGW.2, Rb 24)** <br><sub>`aim9b`</sub> | 7.62 | 4.763 kg HBX-1 x1.6 | wt_datamine: WT `weapons/rocketguns/us_aim9b_sidewinder.blkx :: rocket` | H | 7 |
| **Rb 24J (AIM-9J)** <br><sub>`rb24j`</sub> | 7.62 | 4.763 kg HBX-1 x1.6 | wt_datamine: WT `weapons/rocketguns/swd_rb24j.blkx :: rocket` | H | 1 |
| **R-73** <br><sub>`r73`</sub> | 6.075 | 3.75 kg OKFOL x1.62 | wt_datamine: WT `weapons/rocketguns/su_r_73.blkx :: rocket` | H | 4 |
| **IRIS-T** <br><sub>`iris_t`</sub> | 5.76 | 4.5 kg PBXN-110 x1.28 | wt_datamine: WT `weapons/groundmodels_weapons/127mm_iris_t_user_cannon.blkx :: bullet/rocket` | M | 1 |
| **AIM-9M Sidewinder** <br><sub>`aim9m`</sub> | 4.623 | 3.583 kg PBXN-3 x1.29 | wt_datamine: WT `weapons/rocketguns/us_aim9m_sidewinder.blkx :: rocket` | H | 2 |
| **AIM-9X Sidewinder** <br><sub>`aim9x`</sub> | 4.623 | 3.583 kg PBXN-3 x1.29 | wt_datamine: WT `weapons/groundmodels_weapons/127mm_aim_9x_user_cannon.blkx :: bullet/rocket` | H | 1 |
| **Rb 74(M) (AIM-9L/M)** <br><sub>`rb74m`</sub> | 4.623 | 3.583 kg PBXN-3 x1.29 | wt_datamine: WT `weapons/rocketguns/swd_rb74m.blkx :: rocket` | H | 1 |
| **AIM-9L Sidewinder** <br><sub>`aim9l`</sub> | 4.582 | 3.58 kg PBXN-102 x1.28 | wt_datamine: WT `weapons/rocketguns/us_aim9l_sidewinder.blkx :: rocket` | H | 3 |
| **R-60MK** <br><sub>`r60mk`</sub> | 1.789 | 1.35 kg OKFOL-20 x1.325 | wt_datamine: WT `weapons/rocketguns/su_r_60mk.blkx :: rocket` | H | 1 |
| **R-60** <br><sub>`r60`</sub> | 1.15 | 1.15 kg TNT x1.0 | wt_datamine: WT `weapons/rocketguns/su_r_60.blkx :: rocket` | H | 2 |
| **AIM-92 Stinger (ATAS)** <br><sub>`aim92`</sub> | 0.54 | 0.45 kg HTA x1.2 | wt_datamine: WT `weapons/rocketguns/us_aim92_stinger.blkx :: rocket` | H | 1 |

## Air-to-ground / cruise / anti-radiation missiles

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **Kh-32** <br><sub>`kh32`</sub> | 325.0 | 250.0 kg unknown (RDX/TNT class assumed) x1.3 | estimate: https://en.wikipedia.org/wiki/Kh-22 (Kh-32 warhead 500 kg) | L | 1 |
| **Kh-47M2 Kinzhal (conventional)** <br><sub>`kh47m2`</sub> | 312.0 | 240.0 kg unknown (RDX/TNT class assumed) x1.3 | estimate: https://missilethreat.csis.org/missile/kinzhal/ (480 kg payload) | L | 1 |
| **Kh-55 / Kh-555 (conventional)** <br><sub>`kh55`</sub> | 260.0 | 200.0 kg unknown (RDX/TNT class assumed) x1.3 | estimate: https://missilethreat.csis.org/missile/kh-55/ (Kh-555: 400 kg unitary HE) | L | 1 |
| **Kh-29D (IR) - Kh-29T/TD warhead** <br><sub>`kh29d`</sub> | 186.2 | 116.4 kg TGAF-5M x1.6 | wt_datamine: WT `weapons/rocketguns/su_kh_29td.blkx :: rocket` | M | 1 |
| **Kh-29L/ML** <br><sub>`kh29l`</sub> | 186.2 | 116.4 kg TGAF-5M x1.6 | wt_datamine: WT `weapons/rocketguns/su_kh_29l.blkx :: rocket` | H | 2 |
| **Kh-58U** <br><sub>`kh58`</sub> | 176.0 | 110.0 kg TGAF-5M x1.6 | wt_datamine: WT `weapons/rocketguns/su_kh_58u_a_band.blkx :: rocket` | H | 1 |
| **Kh-38MTE** <br><sub>`kh38mte`</sub> | 152.0 | 95 kg TGAF-5M x1.6 | wt_datamine: WT `weapons/rocketguns/su_kh_38mte.blkx :: rocket` | H | 1 |
| **Kh-25(L)** <br><sub>`kh25l`</sub> | 119.0 | 93 kg TG-40 x1.28 | wt_datamine: WT `weapons/rocketguns/su_kh_25.blkx :: rocket` | H | 1 |
| **Kh-25ML** <br><sub>`kh25ml`</sub> | 119.0 | 93 kg TG-40 x1.28 | wt_datamine: WT `weapons/rocketguns/su_kh_25ml.blkx :: rocket` | H | 2 |
| **Kh-25MT** <br><sub>`kh25mt`</sub> | 119.0 | 93 kg TG-40 x1.28 | wt_datamine: WT `weapons/rocketguns/su_kh_25ml.blkx :: rocket (Kh-25ML warhead)` | M | 1 |
| **Kh-31P** <br><sub>`kh31p`</sub> | 116.5 | 68.5 kg Hexal x1.7 | wt_datamine: WT `weapons/rocketguns/su_kh_31p_l_111.blkx :: rocket` | H | 1 |
| **AGM-84K SLAM-ER** <br><sub>`slam_er`</sub> | 97.345 | 75.461 kg PBXN-3 x1.29 | wt_datamine: WT `weapons/rocketguns/us_agm_84k_slam_er.blkx :: rocket` | H | 1 |
| **AGM-12B Bullpup** <br><sub>`agm12b`</sub> | 58.05 | 43 kg H-6 x1.35 | wt_datamine: WT `weapons/rocketguns/us_agm_12b_bullpup.blkx :: rocket` | H | 2 |
| **AGM-65A/B/D Maverick (WDU-20 shaped charge)** <br><sub>`agm65d`</sub> | 51.195 | 39.08 kg Comp B x1.31 | wt_datamine: WT `weapons/rocketguns/us_agm_65d.blkx :: rocket` | H | 4 |
| **AGM-65E Maverick (WDU-24 blast-frag/penetrator)** <br><sub>`agm65e`</sub> | 44.633 | 36.287 kg PBXN/AFX-108 x1.23 | wt_datamine: WT `weapons/rocketguns/us_agm_65e.blkx :: rocket` | H | 4 |
| **AGM-45A Shrike** <br><sub>`agm45a`</sub> | 29.03 | 22.68 kg PBXN-110 x1.28 | wt_datamine: WT `weapons/rocketguns/us_agm_45a_1.blkx :: rocket` | H | 1 |
| **AGM-88 HARM (A/C)** <br><sub>`agm88`</sub> | 21.63 | 20.6 kg PBXN-107 x1.05 | wt_datamine: WT `weapons/rocketguns/us_agm_88c.blkx :: rocket` | H | 1 |
| **LMUR "Izdeliye 305" (305E)** <br><sub>`lmur`</sub> | 19.2 | 12 kg TGAF-5M x1.6 | wt_datamine: WT `weapons/rocketguns/su_lmur.blkx :: rocket` | H | 4 |
| **AGM-114K Hellfire II** <br><sub>`agm114k`</sub> | 9.427 | 6.686 kg LX-14 x1.41 | wt_datamine: WT `weapons/rocketguns/us_hellfire_agm_114_k.blkx :: rocket` | H | 2 |
| **PARS 3 LR (TRIGAT-LR)** <br><sub>`pars3lr`</sub> | 3.696 | 3.08 kg HTA x1.2 | wt_datamine: WT `weapons/rocketguns/euro_trigat_lr.blkx :: rocket` | H | 2 |

## Bombs

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **FAB-5000M-54** <br><sub>`fab5000`</sub> | 3310.5 | 2207.0 kg TGA-16 x1.5 | wt_datamine: WT `weapons/bombguns/su_fab5000_m54.blkx :: bomb` | M | 1 |
| **FAB-3000M-54** <br><sub>`fab3000`</sub> | 2219.2 | 1387.0 kg TGAG-5 x1.6 | wt_datamine: WT `weapons/bombguns/su_fab_3000m_54.blkx :: bomb` | M | 1 |
| **FAB-1500M-54** <br><sub>`fab1500`</sub> | 675.0 | 675.0 kg TNT x1.0 | wt_datamine: WT `weapons/bombguns/su_fab_1500m_54.blkx :: bomb` | H | 1 |
| **GBU-10 Paveway II (Mk 84)** <br><sub>`gbu10`</sub> | 578.6 | 428.6 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_gbu_10_paveway_2.blkx :: bomb` | H | 1 |
| **GBU-15(V)2/B (Mk 84)** <br><sub>`gbu15v2`</sub> | 578.6 | 428.6 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_2000lb_gbu_15v2.blkx :: bomb` | H | 1 |
| **Mk 84 LDGP (2,000 lb)** <br><sub>`mk84`</sub> | 578.6 | 428.6 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_2000lb_mk_84_ldgp.blkx :: bomb` | H | 1 |
| **FAB-500M-62** <br><sub>`fab500`</sub> | 340.8 | 213.0 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/bombguns/su_fab_500m_62t.blkx :: bomb` | M | 1 |
| **BLU-109 penetrator (bare / GBU-31(V)4/B JDAM)** <br><sub>`blu109`</sub> | 286.4 | 242.7 kg Tritonal x1.18 | wt_datamine: WT `weapons/bombguns/us_2000lb_gbu_27.blkx :: bomb` | H | 2 |
| **GBU-27 Paveway III (BLU-109)** <br><sub>`gbu27`</sub> | 286.4 | 242.7 kg Tritonal x1.18 | wt_datamine: WT `weapons/bombguns/us_2000lb_gbu_27.blkx :: bomb` | H | 1 |
| **GBU-16 Paveway II (Mk 83)** <br><sub>`gbu16`</sub> | 272.4 | 201.8 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_gbu_16_paveway_2.blkx :: bomb` | H | 1 |
| **Generic "500 kg" bomb (dumb/GPS/laser) - Mk 83-class proxy** <br><sub>`generic_500kg_bomb`</sub> | 272.4 | 201.8 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_1000lb_mk_83_ldgp.blkx :: bomb` | L | 6 |
| **Mk 83 LDGP (1,000 lb)** <br><sub>`mk83`</sub> | 272.4 | 201.8 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_1000lb_mk_83_ldgp.blkx :: bomb` | H | 1 |
| **FAB-250M-62** <br><sub>`fab250`</sub> | 160.0 | 100.0 kg TGAF-5 x1.6 | wt_datamine: WT `weapons/bombguns/su_fab_250m_62.blkx :: bomb` | M | 2 |
| **SC 250** <br><sub>`sc250`</sub> | 125.0 | 125.0 kg Fp 60/40 (TNT/AN amatol) x1.0 | wt_datamine: WT `weapons/bombguns/de_sc250.blkx :: bomb` | H | 2 |
| **GBU-12 Paveway II (Mk 82)** <br><sub>`gbu12`</sub> | 117.6 | 87.1 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_gbu_12_paveway_2.blkx :: bomb` | H | 3 |
| **Mk 82 LDGP (500 lb)** <br><sub>`mk82`</sub> | 117.6 | 87.1 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_500lb_mk_82_ldgp.blkx :: bomb` | H | 6 |
| **Mk 82 Snakeye (retarded)** <br><sub>`mk82_snakeye`</sub> | 117.6 | 87.1 kg H-6 x1.35 | wt_datamine: WT `weapons/bombguns/us_500lb_mk_82_ldgp_snakeye.blkx :: bomb` | H | 3 |
| **FAB-100** <br><sub>`fab100`</sub> | 36.02 | 36.02 kg TNT x1.0 | wt_datamine: WT `weapons/bombguns/su_fab100.blkx :: bomb` | M | 5 |
| **SC 50** <br><sub>`sc50`</sub> | 25 | 25 kg Fp 60/40 (TNT/AN amatol) x1.0 | wt_datamine: WT `weapons/bombguns/de_sc50.blkx :: bomb` | H | 2 |
| **Melon (watermelon) bomb - Tom F6F (fictional)** <br><sub>`melon_bomb`</sub> | 10 | - | estimate: estimate | L | 2 |

## Cluster munitions (casings and submunitions)

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **BLU-108/B (4 Skeet EFPs) (per BLU-108)** <br><sub>`blu108`</sub> | 6.01 | 3.78 kg Octol x1.59 | wiki: https://en.wikipedia.org/wiki/BLU-108 (Skeet: 945 g Octol, 4 per BLU-108); multiplier = WT octol | H | 1 |
| **RBK-250 submunition (per bomblet)** <br><sub>`rbk250_bomblet`</sub> | 0.5 | 0.5 kg unknown (TNT assumed) x1.0 | wiki: https://en.wikipedia.org/wiki/RBK-250 ("each bomblet ... weighs 2.8 kg and carries 500 g of explosives") | L | 2 |
| **BLU-97/B Combined Effects Bomb (per bomblet)** <br><sub>`blu97`</sub> | 0.4018 | 0.287 kg Cyclotol x1.4 | wiki: https://en.wikipedia.org/wiki/BLU-97/B_Combined_Effects_Bomb (287 g cyclotol; IM version PBXN-107) | M | 1 |
| **Mk 118 Rockeye bomblet (per bomblet)** <br><sub>`mk118`</sub> | 0.2445 | 0.1861 kg Comp B + tetryl booster | wiki: https://www.bocn.co.uk/threads/clusterbomb-mk-118-rockeye-heat-mod-0-mod-1.91118/ (181 g Comp B or Octol 75/25 + 5.1 g tetryl booster) | M | 2 |
| **DPICM grenade (M42/M77 class) - SBW CM submunition** <br><sub>`dpicm_grenade`</sub> | 0.0432 | 0.03 kg Comp A5 x1.44 | estimate: commonly cited ~30 g Composition A5 per M42/M77 grenade (not confirmed from a primary source); multiplier = WT comp_a | L | 1 |
| **107 mm "CM" cluster rocket carrier (SBW)** <br><sub>`type63_107_cm_casing`</sub> | 0 | - | estimate: cluster carrier rule | L | 3 |
| **CBU-87 CEM dispenser (casing)** <br><sub>`cbu87_casing`</sub> | 0 | - | estimate: cluster rule: casing = 0 | H | 1 |
| **CBU-97 SFW dispenser (casing)** <br><sub>`cbu97_casing`</sub> | 0 | - | estimate: cluster rule: casing = 0 | H | 1 |
| **CBU-99 Rockeye II dispenser (casing)** <br><sub>`cbu99_casing`</sub> | 0 | - | estimate: cluster rule: casing = 0 | H | 2 |
| **RBK-250 dispenser (casing)** <br><sub>`rbk250_casing`</sub> | 0 | - | estimate: cluster rule: casing = 0 | H | 2 |
| **SBW cluster (CM) shell carrier** <br><sub>`cm_shell_casing`</sub> | 0 | - | estimate: SBW CannonShellEntity CM mode (carrier splits into GunGrenadeEntity submunitions) | M | 4 |

## Loitering munitions

| Munition | TNT-eq kg | Filler | Method / source | Conf. | Rows |
|---|---:|---|---|:-:|---:|
| **YX-100 swarm drone (fictional kamikaze drone)** <br><sub>`swarm_drone`</sub> | 0.5 | - | estimate: estimate | L | 3 |

## Estimates, proxies and judgement calls

- **AIM-174B (SM-6 derived)** (`aim174b`) = 36.864 kg - Not in WT. 45% HE fill of a PBXN-110-class explosive assumed (range ~30-45 kg).
- **MBDA Meteor** (`meteor`) = 9.178 kg - Warhead mass not published; AMRAAM-class assumed.
- **Kh-25MT** (`kh25mt`) = 119.0 kg - Kh-25MT is not in WT; it uses the same 90 kg-class warhead as the Kh-25ML.
- **Kh-29D (IR) - Kh-29T/TD warhead** (`kh29d`) = 186.2 kg - No Kh-29D in WT; all Kh-29 variants share the 317-320 kg warhead.
- **Kh-32** (`kh32`) = 325.0 kg - Not in WT. Warhead mass only; 50% HE fill at RE 1.3 assumed (range ~250-400 kg).
- **Kh-47M2 Kinzhal (conventional)** (`kh47m2`) = 312.0 kg - Not in WT. 50% HE fill at RE 1.3 assumed (range ~240-380 kg). Nuclear option 5-50 kt ignored.
- **Kh-55 / Kh-555 (conventional)** (`kh55`) = 260.0 kg - Kh-55 proper is nuclear-only (200-250 kt = 2-2.5e8 kg TNT); value is for the conventional Kh-555 warhead, 50% fill at RE 1.3 assumed.
- **BGM-71E TOW-2A** (`bgm71e`) = 4.836 kg - TOW-2A is not in War Thunder. Main charge taken from WT TOW-2; tip precursor assumed ~0.3 kg LX-14.
- **MILAN 3 (tandem)** (`milan3`) = 2.62 kg - MILAN 3 is not in War Thunder; MILAN 2 main charge + assumed precursor.
- **FGM-148 Javelin** (`javelin`) = 5.64 kg - Explosive mass not published. ~4 kg LX-14-class assumed (40-55% of 8.4 kg -> 4.7-6.5 kg TNT). WT human_weapons fgm_148_javelin_missile.blkx lists 0.4 kg OKFOL-20 - identical to its 9M39 Igla entry, i.e. a placeholder, so rejected.
- **BLU-109 penetrator (bare / GBU-31(V)4/B JDAM)** (`blu109`) = 286.4 kg - No bare BLU-109 or GBU-31(V)4 in WT; the GBU-27 carries the BLU-109 warhead (242.7 kg tritonal).
- **Generic "500 kg" bomb (dumb/GPS/laser) - Mk 83-class proxy** (`generic_500kg_bomb`) = 272.4 kg - Mod-only generic F/A-18E store. Proxied to the Mk 83 / GBU-32 / GBU-16 warhead (the US 1,000 lb class). If treated as a FAB-500M-62 instead: 340.8 kg.
- **Melon (watermelon) bomb - Tom F6F (fictional)** (`melon_bomb`) = 10 kg - Cartoon watermelon bomb: a ~8 L melon-sized charge of cast explosive is ~10-13 kg; 10 kg TNT used.
- **SBW cluster (CM) shell carrier** (`cm_shell_casing`) = 0 kg - Carrier casing only (0, like the cluster-bomb casing rule). The explosive is in the submunitions: see dpicm_grenade (per grenade). Real analogue 155 mm M483A1 = 88 x M42/M46 grenades; ejection charge not counted.
- **DPICM grenade (M42/M77 class) - SBW CM submunition** (`dpicm_grenade`) = 0.0432 kg - Per submunition. SBW spawns 50 per CM shell by default.
- **107 mm "CM" cluster rocket carrier (SBW)** (`type63_107_cm_casing`) = 0 kg - Carrier casing = 0 (submunitions carry the explosive; North Korean Type 75 cluster head: 15 submunitions).
- **CBU-87 CEM dispenser (casing)** (`cbu87_casing`) = 0 kg - Casing only; the explosive is carried by the bomblets (see #bomblet rows).
- **CBU-97 SFW dispenser (casing)** (`cbu97_casing`) = 0 kg - Casing only; the explosive is carried by the bomblets (see #bomblet rows).
- **CBU-99 Rockeye II dispenser (casing)** (`cbu99_casing`) = 0 kg - Casing only; the explosive is carried by the bomblets (see #bomblet rows).
- **RBK-250 dispenser (casing)** (`rbk250_casing`) = 0 kg - Casing only; the explosive is carried by the bomblets (see #bomblet rows).
- **RBK-250 submunition (per bomblet)** (`rbk250_bomblet`) = 0.5 kg - Wikipedia's single-load description (2.8 kg bomblet = PTAB-2.5M class). Explosive type not stated; TNT assumed (TG-50 would be ~0.65 kg). RBK-250 loads vary (PTAB-2.5M, AO-1SCh, ZAB-2.5...).
- **30 mm APDS (H/PJ-11 CIWS)** (`k_30mm_apds_hpj11`) = 0 kg - SBW H/PJ-11 fires tungsten APDS; no filler.
- **YX-100 electromagnetic-gun slug (fictional)** (`k_yx100_railgun`) = 0 kg - Kinetic railgun projectile, no filler.
- **YX-100 swarm drone (fictional kamikaze drone)** (`swarm_drone`) = 0.5 kg - Fictional. Sized like an FPV/loitering kamikaze drone warhead (0.1 kg for Switchblade-300-class up to ~1.5 kg for RPG-warhead FPVs).
- **PTKM-1R top-attack EFP submunition** (`ptkm1r_efp`) = 2.8 kg - The 2.8 kg filling is quoted for the mine; practically all of it is the EFP charge in the launched submunition. Explosive type not published; TNT-equivalent 1.0 assumed (an HMX/OKFOL fill would give ~4.5 kg).
- **Entry Denial Device (fictional, Rainbow Six-style trip charge)** (`edd`) = 0.25 kg - No real-world item; sized as a small door-frame trip charge (~0.1-0.5 kg TNT). SBW in-game blast (ED 60 r3) is well below the M67 (ED 120 r6).
- **107 mm "AP" rocket (SBW medium rocket AP)** (`type63_107_ap`) = 0 kg - SBW gameplay round; treated as kinetic per the AP rule. If it is meant as a HEAT rocket, use ~1.3 kg.
- **TBG-7V thermobaric (RPG-7)** (`tbg7v`) = 2.783 kg - Thermobaric mix counted at RE ~1.3 (assumption; open-air equivalence of such fuel-rich mixes is quoted anywhere from 1.0 to 2.0, range 2.2-4.1 kg). Also SBW drone payload labelled "Yasin 105 TBG" (real Al-Yasin 105 is a tandem HEAT round).
- **YX-100 EM-gun HE shell (fictional)** (`yx100_he`) = 5.24 kg - YX-100 is a fictional SBW tank with an electromagnetic gun; no real round exists. Proxy = a 125 mm HE-FRAG (3OF26, 5.24 kg). In-game it hits harder (ED 200 r11) than the SBW T-90A HE (ED 120 r10), so a 6-10 kg value would also be defensible.

## Rows mapped to null

- 187 row(s): vehicle destruction / cook-off / wreck explosion - not a munition
- 1 row(s): energy/laser weapon (Annihilator Energy Cannon) - not an explosive munition
- 1 row(s): laser weapon (Prism Tank) - not an explosive munition
- 1 row(s): energy weapon (Red Alert 3 Wave-Force Tower) - not an explosive munition
- 1 row(s): shotgun-pellet fire mode (Override.ExplosionDamage=0, projectile=superbwarfare:projectile); inventory row inherits the base gun_grenade/ER by mistake
- 1 row(s): melee fire mode (ProjectileAmount 0); ED/ER inherited from base gun, no munition
- 1 row(s): fictional "Super Star" projectile - not an explosive munition
- 1 row(s): PTKM-1R mine body/launcher; its 2.8 kg filling is counted in the EFP submunition row (superbwarfare:ptkm_projectile) to avoid double counting
- 1 row(s): entity-init placeholder overwritten by the firing gun's data; the real rounds are the per-vehicle rows
- 1 row(s): ammo perk (scales bullet damage), not a munition
- 1 row(s): ammo perk (scales gun ED/ER), not a munition
- 1 row(s): damage perk (head-shot burst), not a munition
- 1 row(s): CIWS projectile-intercept FX burst, not a munition

## Inventory issues noticed

- `superbwarfare:rpg[AmmoType[1]]` and `superbwarfare:secondary_cataclysm[AmmoType[1..2]]`: the generator takes `Projectile` from the merged Override, but these AmmoType entries declare `Projectile` outside `Override`, so the rows show the base projectile and inherited ED/ER.
- 75/88 mm PzGr 39 family is filed under `tank_shell_apfsds`; they are APCBC-HE with a filler.
- `MILAN 1` rows on marder_1a5 carry calibre 115 mm (MILAN 2 calibre).
- `S13` (hdc=HE): the plain S-13 is the concrete-penetrating rocket; if the HE-frag S-13OF was intended use 10.63 kg.


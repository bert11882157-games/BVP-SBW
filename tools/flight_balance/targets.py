"""Owner's speed balance (2026-09-28), HUD km/h with Mach 1 = 400 km/h.

FIRST_SOFT_CAP: each aircraft's first soft cap - roughly where it is willing to fly in level flight. Level flight (or
a shallow dive) passes it somewhat and slowly; steep dives go well past it. The dogfighting band for jets is
160-300 km/h. Only the MiG-21, F-104 and F-15 reach the global soft cap (650) and pass it on afterburner.

AERO_FIXES: engineering values that were data errors (drag far above the real type, a copied airframe).
"""
FIRST_SOFT_CAP = {
    # propeller aircraft
    'f8f_1': 205, 'p_51d': 205, 'supermarine_spitfire_griffon': 205, 'yak_3': 195, 'yak_9u': 195, 'j_26': 205,
    'saab_j_21a_1': 190, 'il_10': 170, 'ju_87_b2': 145, 'an_12b': 175, 'c_130h': 170, 'tu_95ms': 215,
    # first-generation and subsonic jets: Mach 0.5-0.6 cruise, near Mach 1 only in dives
    'yak_15p': 185, 'mig_9': 200, 'ho_229': 190, 'meteor_f_8': 215, 'f9f_2': 220, 'f2h_2': 220,
    'md_450_ouragan': 225, 'mig_15bis': 235, 'j_2': 235, 'saab_29_tunnan': 240, 'sabre_mk_6': 245,
    'f_84f': 245, 'fiat_g_91': 240, 'a_7d': 245, 'su_25': 230, 'su_39': 230, 'a_10': 175, 'j_5': 250,
    'f_86k': 255, 'saab_32_lansen': 260, 'f3h': 280, 'b_47e': 235, 'm_50a': 260, 'il_76m': 230,
    # transonic / early supersonic: around Mach 0.85, Mach 1 needs afterburner and a dive
    'super_mystere': 330, 'q_5': 340, 'f_100c': 350, 'mig_19s': 350, 'f_5a': 330,
    # Mach 2 class: agile, supersonic when energy is built up, fights happen below Mach 1
    'mig_29': 440, 'su_27': 450, 'j_11a': 450, 'su_30': 445, 'su_35': 455, 'j_15d': 445, 'su_57': 460,
    'f_16b': 435, 'f_16c': 435, 'j_10a': 440, 'eurofighter_typhoon': 450, 'rafale': 435,
    'saab_jas_39_gripen': 430, 'fa_18e': 415, 'f_4c': 430, 'f_14a': 440, 'f_14d': 440, 'f_111f': 440,
    'mirage_5': 420, 'mirage_f1': 425, 'saab_35_draken': 430, 'saab_37_viggen': 430, 'su_17': 420,
    'su_24': 410, 'su_9': 440, 'panavia_tornado_ids_marineflieger': 420, 'mig_23mld': 450, 'f_8h': 420,
    'b_1b': 400, 'tu_22m': 400,
    # the owner's three: past the global soft cap on afterburner, level or diving
    'mig_21bis': 650, 'f_104g': 650, 'f_15c': 650, 'f_15e': 650,
}

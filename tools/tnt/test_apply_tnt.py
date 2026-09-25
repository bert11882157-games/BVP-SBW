#!/usr/bin/env python3
"""Self-contained tests for apply_tnt.py on a synthetic repo (no real data files are touched).

Run: python3 tools/tnt/test_apply_tnt.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apply_tnt.py")
V = "bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles"
G = "bvp/src/generated/resources/data/berts_vehicle_pack/sbw/guns"
S = "bvp/src/generated/resources/data/berts_vehicle_pack/sbw/aircraft_stores"
SG = "sbw/src/main/resources/data/superbwarfare/sbw/guns"
D = "sbw/src/main/resources/data/superbwarfare/sbw/drone_attachments"
DEFAULTS = "sbw/src/main/resources/data/superbwarfare/blast/tnt_defaults.json"

TANK = """{
  "ID": "tank",
  "Weapons": {
    "Cannon": {
      "Damage": 620,
      "ExplosionDamage": 80,
      "ExplosionRadius": 4,
      "AmmoType": [
        {"Ammo": "ap", "Override": {"Damage": 250, "ExplosionDamage": 0}},
        {"Ammo": "he", "Override": {"Damage": 100, "ExplosionDamage": 200, "ExplosionRadius": 9}},
        {"Ammo": "heat", "Override": {"Damage": 140, "ExplosionRadius": 9}},
        {"Ammo": "bare"}
      ]
    },
    "Coax": {"Damage": 8, "ProjectileBeltAmmoType": [{"Override": {"ExplosionDamage": 3, "ExplosionRadius": 1}}]}
  }
}
"""
POD = """{"Name": "pod", "ExplosionDamage": 20, "ExplosionRadius": 3, "AmmoType": [{"Override": {"Damage": 5}}]}
"""
COMPACT_GUN = '{"Damage": 80, "ExplosionDamage": 120, "ExplosionRadius": 6, "AmmoType": "superbwarfare:rocket"}'
STORES = {
    "bomb.json": {"Schema": 1, "Name": "b", "Category": "BOMB", "MassKg": 227,
                  "Bomb": {"Mode": "DUMB", "BlastDamage": 500, "BlastRadius": 20, "Gravity": 0.05}},
    "cluster.json": {"Schema": 1, "Name": "c", "Category": "BOMB", "MassKg": 430,
                     "Bomb": {"Mode": "DUMB", "BlastDamage": 0, "BlastRadius": 0,
                              "Cluster": {"Count": 10, "BombletDamage": 40, "BombletRadius": 3}}},
    "aam.json": {"Schema": 1, "Name": "a", "Category": "AIR_TO_AIR", "Guidance": {"Mode": "INFRARED"},
                 "Flight": {"Damage": 90, "BlastRadius": 5}},
    "aam_default.json": {"Schema": 1, "Name": "d", "Category": "AIR_TO_AIR", "Guidance": {"Mode": "INFRARED"}},
    "rockets.json": {"Schema": 1, "Name": "r", "Category": "ROCKET_POD", "LaunchGunProfile": "berts_vehicle_pack:pod"},
    "dummy.json": {"Schema": 1, "Name": "x", "Category": "AIR_TO_AIR"},
}
INVENTORY = [
    ("berts_vehicle_pack:tank#destroy", V + "/tank.json"),
    ("berts_vehicle_pack:tank/Cannon", V + "/tank.json"),
    ("berts_vehicle_pack:tank/Cannon[AmmoType[1]]", V + "/tank.json"),
    ("berts_vehicle_pack:tank/Cannon[AmmoType[2]]", V + "/tank.json"),
    ("berts_vehicle_pack:tank/Coax[ProjectileBeltAmmoType[0]]", V + "/tank.json"),
    ("berts_vehicle_pack:guns/pod", G + "/pod.json"),
    ("superbwarfare:rpg", SG + "/rpg.json"),
    ("berts_vehicle_pack:aircraft_stores/bomb", S + "/bomb.json"),
    ("berts_vehicle_pack:aircraft_stores/cluster", S + "/cluster.json"),
    ("berts_vehicle_pack:aircraft_stores/cluster#bomblet", S + "/cluster.json"),
    ("berts_vehicle_pack:aircraft_stores/aam", S + "/aam.json"),
    ("berts_vehicle_pack:aircraft_stores/aam_default", S + "/aam_default.json"),
    ("berts_vehicle_pack:aircraft_stores/rockets", S + "/rockets.json"),
    ("berts_vehicle_pack:aircraft_stores/dummy", S + "/dummy.json"),
    ("superbwarfare:drone_attachment/c4_bomb", D + "/c4_bomb.json"),
    ("superbwarfare:hand_grenade", "sbw/src/main/kotlin/com/atsuishio/superbwarfare/config/server/ExplosionConfig.kt"),
]


class ApplyTntTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="apply_tnt_")
        self.repo = os.path.join(self.root, "repo")
        for rel, text in [(V + "/tank.json", TANK), (G + "/pod.json", POD), (SG + "/rpg.json", COMPACT_GUN),
                          (D + "/c4_bomb.json", '{\n  "Item": "superbwarfare:c4_bomb",\n  "ExplosionDamage": 300\n}'),
                          (DEFAULTS, '{\n  "entries": {}\n}\n')] + \
                [(S + "/" + name, json.dumps(data, indent=2) + "\n") for name, data in STORES.items()]:
            self.write(rel, text)
        with open(os.path.join(self.root, "inventory.tsv"), "w") as handle:
            handle.write("id\tname\tkind\tcaliber_mm\tfile\texplosion_damage\texplosion_radius\tnotes\n")
            for rid, rel in INVENTORY:
                handle.write(f"{rid}\tn\tk\t\t{rel}\t0\t0\t\n")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, rel, text):
        path = os.path.join(self.repo, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as handle:
            handle.write(text)

    def read(self, rel):
        with open(os.path.join(self.repo, rel)) as handle:
            return handle.read()

    def run_script(self, rows, *extra):
        table = {"munitions": [{"key": "he", "tnt_kg": 3.4}, {"key": "bomb", "tnt_kg": 120},
                               {"key": "tiny", "tnt_kg": 0.05}, {"key": "aam", "tnt_kg": 12.5},
                               {"key": "none", "tnt_kg": 0}], "rows": rows}
        table_path = os.path.join(self.root, "table.json")
        with open(table_path, "w") as handle:
            json.dump(table, handle)
        result = subprocess.run([sys.executable, SCRIPT, "--table", table_path, "--inventory",
                                 os.path.join(self.root, "inventory.tsv"), "--repo", self.repo, *extra],
                                capture_output=True, text=True)
        return result

    def snapshot(self):
        out = {}
        for folder, _, names in os.walk(self.repo):
            for name in names:
                path = os.path.join(folder, name)
                with open(path) as handle:
                    out[os.path.relpath(path, self.repo)] = handle.read()
        return out

    def test_writes_every_target_kind_and_is_idempotent(self):
        rows = {
            "berts_vehicle_pack:tank/Cannon": "he",
            "berts_vehicle_pack:tank/Cannon[AmmoType[1]]": "he",
            "berts_vehicle_pack:tank/Cannon[AmmoType[2]]": None,
            "berts_vehicle_pack:tank/Coax[ProjectileBeltAmmoType[0]]": "tiny",
            "berts_vehicle_pack:guns/pod": "he",
            "superbwarfare:rpg": "he",
            "berts_vehicle_pack:aircraft_stores/bomb": "bomb",
            "berts_vehicle_pack:aircraft_stores/cluster": None,
            "berts_vehicle_pack:aircraft_stores/cluster#bomblet": "tiny",
            "berts_vehicle_pack:aircraft_stores/aam": "aam",
            "berts_vehicle_pack:aircraft_stores/aam_default": "aam",
            "berts_vehicle_pack:aircraft_stores/rockets": "he",
            "berts_vehicle_pack:aircraft_stores/dummy": "aam",
            "berts_vehicle_pack:tank#destroy": "bomb",
            "superbwarfare:drone_attachment/c4_bomb": "tiny",
            "superbwarfare:hand_grenade": "tiny",
        }
        before = self.snapshot()
        dry = self.run_script(rows, "--dry-run")
        self.assertEqual(0, dry.returncode, dry.stdout + dry.stderr)
        self.assertEqual(before, self.snapshot(), "dry run writes nothing")
        first = self.run_script(rows)
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        tank = json.loads(self.read(V + "/tank.json"))["Weapons"]
        self.assertEqual(3.4, tank["Cannon"]["TntEquivalentKg"])
        ammo = tank["Cannon"]["AmmoType"]
        self.assertEqual(0.0, ammo[0]["Override"]["TntEquivalentKg"], "unlisted AP ammo must not inherit the HE charge")
        self.assertEqual(3.4, ammo[1]["Override"]["TntEquivalentKg"])
        self.assertEqual(0.0, ammo[2]["Override"]["TntEquivalentKg"], "a null override of a charged base is zeroed")
        self.assertNotIn("Override", ammo[3], "an entry without Override is the base munition and inherits")
        self.assertEqual(0.05, tank["Coax"]["ProjectileBeltAmmoType"][0]["Override"]["TntEquivalentKg"])
        self.assertIn('"ExplosionRadius": 4,\n      "TntEquivalentKg": 3.4,', self.read(V + "/tank.json"))
        pod = json.loads(self.read(G + "/pod.json"))
        self.assertEqual(3.4, pod["TntEquivalentKg"])
        self.assertNotIn("TntEquivalentKg", pod["AmmoType"][0]["Override"], "BVP gun profile overrides inherit")
        self.assertEqual('{"Damage": 80, "ExplosionDamage": 120, "ExplosionRadius": 6, "TntEquivalentKg": 3.4, '
                         '"AmmoType": "superbwarfare:rocket"}', self.read(SG + "/rpg.json"))
        self.assertEqual(120.0, json.loads(self.read(S + "/bomb.json"))["Bomb"]["TntEquivalentKg"])
        cluster = json.loads(self.read(S + "/cluster.json"))["Bomb"]
        self.assertNotIn("TntEquivalentKg", cluster)
        self.assertEqual(0.05, cluster["Cluster"]["BombletTntEquivalentKg"])
        self.assertEqual(12.5, json.loads(self.read(S + "/aam.json"))["Flight"]["TntEquivalentKg"])
        self.assertEqual(12.5, json.loads(self.read(S + "/aam_default.json"))["TntEquivalentKg"])
        self.assertNotIn("TntEquivalentKg", json.loads(self.read(S + "/dummy.json")))
        self.assertEqual(0.05, json.loads(self.read(D + "/c4_bomb.json"))["TntEquivalentKg"])
        self.assertEqual({"superbwarfare:hand_grenade": 0.05}, json.loads(self.read(DEFAULTS))["entries"])
        self.assertIn("store_not_launchable", first.stdout)
        after = self.snapshot()
        again = self.run_script(rows)
        self.assertEqual(0, again.returncode)
        self.assertEqual(after, self.snapshot(), "second run changes nothing")
        self.assertIn("files changed: 0", again.stdout)

        cleared = self.run_script({rid: None for rid in rows})
        self.assertEqual(0, cleared.returncode, cleared.stdout)
        self.assertEqual(before, self.snapshot(), "mapping every row to null restores the original files")

    def test_invalid_tables_write_nothing(self):
        before = self.snapshot()
        for rows in ({"berts_vehicle_pack:tank/Cannon": "missing"}, {"nope:row": "he"}):
            result = self.run_script(rows)
            self.assertEqual(2, result.returncode, result.stdout)
            self.assertIn("ERRORS", result.stdout)
        self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main(verbosity=2)

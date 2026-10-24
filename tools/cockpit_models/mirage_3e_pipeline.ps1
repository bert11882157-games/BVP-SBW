param([switch]$NoCockpit)
# Mirage IIIE full pipeline (as mirage3e\run_mirage3e_cockpit.ps1 without the checks): base airframe -> canopy -> exterior restore -> cockpit -> nozzles
$R = 'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\repo'
$W = 'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2'
$py = 'C:\Users\udstu\Documents\Superb warfare\work\canopy_rebuild\venv\Scripts\python.exe'
$env:PYTHONUTF8 = 1
$G = 'bvp\src\generated\resources'
$files = @("$G\assets\berts_vehicle_pack\custom_geo\mirage_3e.geo.json","$G\assets\berts_vehicle_pack\geo\native_fallback\mirage_3e.geo.json","$G\assets\berts_vehicle_pack\sbw\vehicles\mirage_3e.json","$G\data\berts_vehicle_pack\sbw\vehicles\mirage_3e.json","$G\assets\berts_vehicle_pack\textures\entity\mirage_3e.png","$G\assets\berts_vehicle_pack\textures\entity_dead\mirage_3e.png")
Set-Location $R
foreach ($f in $files) { Copy-Item "$W\mirage3e\base\$f" "$R\$f" -Force }
# pass 1 builds the airframe, canopy and exterior restore; pass 2 rebuilds the canopy against that finished skin
Remove-Item Env:\MIRAGE_SKIN_REF -ErrorAction SilentlyContinue
& $py tools/cockpit_models/canopy_mirage_3e.py | Out-Null
& $py "$W\mirage3e\make_restore.py" "$W\mirage3e\restore_list.json" | Out-Null
& $py "$W\checks\restore_exterior.py" mirage_3e "$W\mirage3e\restore_list.json" wreck_fuselage_1__hull | Out-Null
Copy-Item "$R\$($files[0])" "$W\mirage3e\skin_ref.geo.json" -Force
foreach ($f in $files) { Copy-Item "$W\mirage3e\base\$f" "$R\$f" -Force }
$env:MIRAGE_SKIN_REF = "$W\mirage3e\skin_ref.geo.json"
& $py tools/cockpit_models/canopy_mirage_3e.py 2>&1 | select -last 3
Remove-Item Env:\MIRAGE_SKIN_REF
& $py "$W\mirage3e\make_restore.py" "$W\mirage3e\restore_list.json" 2>&1 | select -last 2
& $py "$W\checks\restore_exterior.py" mirage_3e "$W\mirage3e\restore_list.json" wreck_fuselage_1__hull 2>&1 | select -last 2
if (-not $NoCockpit) { & $py tools/cockpit_models/mirage_3e.py 2>&1 | select -last 2 }
& $py tools/cockpit_models/nozzle_check.py mirage_3e --fix 2>&1 | select -last 2
& $py tools/cockpit_models/mirage_3e_muzzle_sync.py 2>&1 | select -last 1
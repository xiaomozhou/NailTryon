$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$catalog = Get-Content -Raw "$PSScriptRoot\app\src\main\java\com\example\nailtryon\NailCatalog.kt"
$categories = @("纯色美甲","幻彩美甲","手绘美甲","主题美甲","立体美甲")
foreach ($category in $categories) { if ($catalog -notmatch [regex]::Escape($category)) { throw "Missing category: $category" } }
$main = Get-Content -Raw "$PSScriptRoot\app\src\main\java\com\example\nailtryon\MainActivity.kt"
foreach ($required in @("试戴","拍照","从相册选择","ModalBottomSheet","历史记录")) { if ($main -notmatch [regex]::Escape($required)) { throw "Missing flow element: $required" } }
$assets = Get-ChildItem "$PSScriptRoot\app\src\main\res\drawable" -File
if ($assets.Count -lt 4) { throw "Not enough bundled assets" }
Write-Output "PASS: catalog, upload sheet, result flow, history flow, and bundled assets validated."

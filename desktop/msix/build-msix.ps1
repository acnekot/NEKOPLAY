# 把 createDistributable 产出的免安装目录打成 Microsoft Store 用的 MSIX（不签名：上传到 Partner Center 后由微软签名）
# 用法：pwsh desktop/msix/build-msix.ps1 -AppDir desktop/build/compose/binaries/main/app/NEKOPLAY -Out out/NEKOPLAY-store.msix
# 身份信息从环境变量读取（Partner Center → 产品管理 → 产品标识）：
#   MSSTORE_IDENTITY_NAME、MSSTORE_PUBLISHER（形如 CN=XXXXXXXX-…）、MSSTORE_PUBLISHER_DISPLAY
#   没配置时用占位值，包可以本地验证结构，但不能上传
param([Parameter(Mandatory)][string]$AppDir, [Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $AppDir 'NEKOPLAY.exe'))) { throw "找不到 $AppDir\NEKOPLAY.exe，先运行 createDistributable" }

# 版本：Version.kt 的 0.y.z → 1.y.z.0（与 msi / exe 一致；商店要求第四段为 0）
$verKt = Get-Content (Join-Path $here '..\..\shared\src\cc\catsuki\nekoplay\Version.kt') -Raw
if ($verKt -notmatch 'APP_VERSION = "(\d+)\.(\d+)\.(\d+)"') { throw 'Version.kt 里没有 x.y.z 版本号' }
$version = "1.$($Matches[2]).$($Matches[3]).0"

$name = if ($env:MSSTORE_IDENTITY_NAME) { $env:MSSTORE_IDENTITY_NAME } else { 'NEKOPLAY.Placeholder' }
$pub = if ($env:MSSTORE_PUBLISHER) { $env:MSSTORE_PUBLISHER } else { 'CN=NEKOPLAY-Placeholder' }
$pubName = if ($env:MSSTORE_PUBLISHER_DISPLAY) { $env:MSSTORE_PUBLISHER_DISPLAY } else { 'NEKOPLAY' }
if (-not $env:MSSTORE_IDENTITY_NAME) { Write-Host '::warning::未配置 MSSTORE_IDENTITY_NAME / MSSTORE_PUBLISHER，MSIX 使用占位身份，只能用于结构校验，不能上传商店' }

$stage = Join-Path ([IO.Path]::GetTempPath()) ('nekoplay-msix-' + [guid]::NewGuid())
Copy-Item -Recurse $AppDir $stage
Copy-Item -Recurse (Join-Path $here 'Assets') (Join-Path $stage 'Assets')
$esc = { param($s) [Security.SecurityElement]::Escape($s) }
(Get-Content (Join-Path $here 'AppxManifest.template.xml') -Raw) `
  -replace '\{\{IDENTITY_NAME\}\}', (& $esc $name) -replace '\{\{PUBLISHER\}\}', (& $esc $pub) `
  -replace '\{\{PUBLISHER_DISPLAY\}\}', (& $esc $pubName) -replace '\{\{VERSION\}\}', $version |
  Set-Content -Encoding UTF8 (Join-Path $stage 'AppxManifest.xml')

# Windows SDK 里最新的 makeappx.exe
$makeappx = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin\*\x64\makeappx.exe' -ErrorAction SilentlyContinue |
  Sort-Object { [version]($_.Directory.Parent.Name) } | Select-Object -Last 1
if (-not $makeappx) { throw '找不到 makeappx.exe（需要 Windows 10/11 SDK）' }
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
& $makeappx.FullName pack /d $stage /p $Out /o /h SHA256
if ($LASTEXITCODE -ne 0) { throw "makeappx 失败（$LASTEXITCODE）" }
Remove-Item -Recurse -Force $stage
Write-Host "MSIX: $Out  identity=$name  version=$version"

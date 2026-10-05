[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $LibrariesDirectory,
    [Parameter(Mandatory = $true)][string] $ModsDirectory,
    [string] $ForgeClientJar,
    [string] $JdkDirectory,
    [string] $OutputDirectory
)
$ErrorActionPreference = 'Stop'
function Get-RequiredPath {
    param([string] $Path, [bool] $Directory)
    $taskItem = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($taskItem.PSIsContainer -ne $Directory) { throw "Unexpected path type: $Path" }
    return $taskItem.FullName
}
$taskLibraries = Get-RequiredPath $LibrariesDirectory $true
$taskMods = Get-RequiredPath $ModsDirectory $true
$taskQuestsCandidates = @(Get-ChildItem -LiteralPath $taskMods -Filter '*ftb-quests-forge*.jar')
if ($taskQuestsCandidates.Count -ne 1) { throw 'Expected exactly one FTB Quests jar in -ModsDirectory' }
$taskQuests = $taskQuestsCandidates[0].FullName
if ($ForgeClientJar) {
    $taskForge = Get-RequiredPath $ForgeClientJar $false
} else {
    $taskCandidates = @(Get-ChildItem -LiteralPath $taskLibraries -Filter 'forge-1.16.5-36.2.*-client.jar' -Recurse)
    if ($taskCandidates.Count -ne 1) { throw 'Pass -ForgeClientJar when multiple Forge client jars are cached' }
    $taskForge = $taskCandidates[0].FullName
}
if ($JdkDirectory) {
    $taskJdk = Get-RequiredPath $JdkDirectory $true
} elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
    $taskJdk = Get-RequiredPath $env:JAVA_HOME $true
} else {
    $taskJdk = Split-Path (Split-Path (Get-Command javac.exe -CommandType Application -ErrorAction Stop).Source)
}
$taskJavac = Get-RequiredPath (Join-Path $taskJdk 'bin/javac.exe') $false
$taskJava = Get-RequiredPath (Join-Path $taskJdk 'bin/java.exe') $false
$taskJar = Get-RequiredPath (Join-Path $taskJdk 'bin/jar.exe') $false
$taskMetadata = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'resources/META-INF/mods.toml') -Raw
$taskVersion = [regex]::Match($taskMetadata, '(?m)^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$').Groups[1].Value
if (-not $taskVersion) { throw 'Missing patch version' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'dist' }
$taskOutputDir = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName
$taskBuild = Join-Path $PSScriptRoot ('build/' + [guid]::NewGuid().ToString('N'))
$taskClasses = (New-Item -ItemType Directory -Force -Path (Join-Path $taskBuild 'classes')).FullName
$taskTests = (New-Item -ItemType Directory -Force -Path (Join-Path $taskBuild 'tests')).FullName
$taskClasspath = (@($taskForge) + (Get-ChildItem -LiteralPath $taskMods -Filter '*.jar').FullName + (Get-ChildItem -LiteralPath $taskLibraries -Filter '*.jar' -Recurse).FullName) -join [IO.Path]::PathSeparator
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $taskJavac --release 11 -proc:none -encoding UTF-8 -cp $taskClasspath -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed for the selected Forge / FTB dependencies' }
$taskTestSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $taskJavac --release 11 -proc:none -encoding UTF-8 -cp ($taskClasses + [IO.Path]::PathSeparator + $taskClasspath) -d $taskTests @taskTestSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
$taskAsm = @(Get-ChildItem -LiteralPath (Join-Path $taskLibraries 'org/ow2/asm') -Filter '*.jar' -Recurse | Sort-Object { [version]$_.Directory.Name } -Descending | ForEach-Object { $_.FullName })
$taskTestClasspath = (@($taskTests, $taskClasses, (Join-Path $PSScriptRoot 'tests/resources'), (Join-Path $PSScriptRoot 'resources')) + $taskAsm + @($taskClasspath)) -join [IO.Path]::PathSeparator
& $taskJava -cp $taskTestClasspath RepeatVerification $taskQuests
if ($LASTEXITCODE -ne 0) { throw 'Policy or Mixin verification failed; no new jar packaged' }
& $taskJava -cp $taskTestClasspath RepeatVerification $taskQuests SERVER
if ($LASTEXITCODE -ne 0) { throw 'Dedicated-server Mixin verification failed; no new jar packaged' }
$taskOutput = Join-Path $taskOutputDir ('ftb-quests-repeatable-1.16.5-' + $taskVersion + '.jar')
& $taskJar cfm $taskOutput (Join-Path $PSScriptRoot 'resources/META-INF/MANIFEST.MF') -C $taskClasses . -C (Join-Path $PSScriptRoot 'resources') .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
$taskHash = Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256
Set-Content -LiteralPath (Join-Path $taskOutputDir 'SHA256SUMS.txt') -Encoding ascii -Value ($taskHash.Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($taskOutput))
Write-Output ('Built: ' + $taskOutput)
Write-Output ('SHA256: ' + $taskHash.Hash)

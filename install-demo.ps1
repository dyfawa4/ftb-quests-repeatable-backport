[CmdletBinding()]
param([Parameter(Mandatory = $true)][string] $ChapterFile)
$ErrorActionPreference = 'Stop'
$taskChapter = (Get-Item -LiteralPath $ChapterFile).FullName
if ([IO.Path]::GetExtension($taskChapter) -ne '.snbt') { throw 'Select an FTB Quests chapter .snbt file' }
if (Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like '*Minecraft*' }) {
    throw 'Exit Minecraft first, so FTB cannot overwrite the modified chapter on shutdown.'
}
$taskOriginal = [IO.File]::ReadAllText($taskChapter)
if ($taskOriginal.Contains('questrepeat.zeroMoneyDemo')) { Write-Output 'Demo task already installed.'; return }
if ($taskOriginal -notmatch '(?m)^\s*quests:\s*\[') { throw 'The selected file has no quests list' }
$taskEnding = [regex]::Match($taskOriginal, '(?s)\r?\n\t\]\s*\}\s*$')
if (-not $taskEnding.Success) { throw 'Unsupported chapter layout; original file left unchanged' }
$taskFragment = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'examples/zero-money-refresh.snbt'))
$taskDefinitions = Split-Path $taskChapter
$taskExisting = (Get-ChildItem -LiteralPath $taskDefinitions -Filter '*.snbt' | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n"
foreach ($taskToken in @('__QUEST_ID__', '__TASK_ID__', '__REWARD_ID__')) {
    do { $taskId = '6' + [guid]::NewGuid().ToString('N').Substring(0,15).ToUpperInvariant() } while ($taskExisting.Contains($taskId))
    $taskExisting += $taskId
    $taskFragment = $taskFragment.Replace($taskToken, $taskId)
}
$taskIndented = ($taskFragment.Trim() -split '\r?\n' | ForEach-Object { "`t`t" + $_ }) -join "`n"
$taskModified = $taskOriginal.Insert($taskEnding.Index, "`n" + $taskIndented)
$taskBackups = (New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'backups')).FullName
$taskBackup = Join-Path $taskBackups ([IO.Path]::GetFileNameWithoutExtension($taskChapter) + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.snbt')
Copy-Item -LiteralPath $taskChapter -Destination $taskBackup
[IO.File]::WriteAllText($taskChapter, $taskModified, [Text.UTF8Encoding]::new($false))
Write-Output ('Installed zero-money repeatable demo in: ' + $taskChapter)
Write-Output ('Backup: ' + $taskBackup)

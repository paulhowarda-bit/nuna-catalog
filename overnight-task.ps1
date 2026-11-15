<#
The Windows side of the overnight run (overnight.sh, which says what the run does).

  .\overnight-task.ps1 -Register     registers the task "keel overnight": every day at 00:00, waking the PC,
                                     while Paul is signed in (Docker Desktop runs only then)
  .\overnight-task.ps1 -Unregister   removes it
  .\overnight-task.ps1               what the task runs: overnight.sh, with Windows kept awake until it ends
  .\overnight-task.ps1 -Dry          the same with --dry (-Force: with --force)

Register it from the main checkout, so the task runs the copy of overnight.sh that main has. Each start and end
is logged to task.log in the overnight folder (keel-overnight next to the main checkout), with PowerShell's
transcript in task-transcript.log; an error before that folder is found goes to %TEMP%\keel-overnight-task.log.
#>
param([switch]$Register, [switch]$Unregister, [string]$TaskName = 'keel overnight', [switch]$Dry, [switch]$Force)

$ErrorActionPreference = 'Stop'
$bash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
$script = Join-Path $PSScriptRoot 'overnight.sh'
$pass = @()
if ($Dry) { $pass += '--dry' }
if ($Force) { $pass += '--force' }

if ($Register) {
    $arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`""
    if ($Dry) { $arguments += ' -Dry' }
    if ($Force) { $arguments += ' -Force' }
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments -WorkingDirectory $PSScriptRoot
    $trigger = New-ScheduledTaskTrigger -Daily -At '00:00'
    $settings = New-ScheduledTaskSettingsSet -WakeToRun -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit (New-TimeSpan -Hours 8)
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
        -Principal $principal -Force `
        -Description 'keel-catalog: heavy build, Ceph leg, nightly and bites on main, if code changed (overnight.sh)' |
        Out-Null
    Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo | Select-Object TaskName, NextRunTime
    return
}
if ($Unregister) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    return
}

# Anything that stops this script before overnight.sh writes its own log lands here.
trap {
    Add-Content (Join-Path $env:TEMP 'keel-overnight-task.log') "$(Get-Date -Format s) $($_ | Out-String)"
    exit 1
}
$common = (& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir)
$home_ = Join-Path (Split-Path (Split-Path $common)) 'keel-overnight'
New-Item -ItemType Directory -Force $home_ | Out-Null
$log = Join-Path $home_ 'task.log'
Start-Transcript -Path (Join-Path $home_ 'task-transcript.log') -Append | Out-Null
Add-Content $log "$(Get-Date -Format s) start: $script $pass"

# ES_CONTINUOUS | ES_SYSTEM_REQUIRED: Windows does not sleep while this process asks it not to, and the request
# ends with the process. It changes no power setting.
Add-Type -Namespace Keel -Name Power -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);
'@
[Keel.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null
try {
    $ErrorActionPreference = 'Continue'
    & $bash -l $script @pass
    $code = $LASTEXITCODE
} finally {
    [Keel.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
}
Add-Content $log "$(Get-Date -Format s) end: exit $code"
Stop-Transcript | Out-Null
exit $code

# Owlmic microphone setup: installs the virtual microphone driver if it's missing, names it "Owlmic",
# and leaves the user's own default speakers and microphone exactly as they were.
#
# Run by the installer (-Silent). Needs administrator rights.
# The driver files come from -DriverDir, by default the "driver" folder next to this script.
# Exit codes: 0 ready, 3010 ready after Windows restarts, 1 failed.

param (
    [switch]$Silent = $false,
    [string]$DriverDir = ""
)

$ErrorActionPreference = "Stop"
if (-not $DriverDir) { $DriverDir = Join-Path $PSScriptRoot "driver" }

function Say([string]$text, [string]$color = "Cyan") {
    if (-not $Silent) { Write-Host "[owlmic] $text" -ForegroundColor $color }
}

function Finish([int]$code) {
    if (-not $Silent) { Read-Host "Press Enter to close" | Out-Null }
    exit $code
}

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    if ($Silent) { exit 1 }
    Say "Setting up the Owlmic microphone needs administrator rights. Asking Windows..." "Yellow"
    $elevated = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -DriverDir `"$DriverDir`""
    try {
        $proc = Start-Process powershell.exe -ArgumentList $elevated -Verb RunAs -PassThru -Wait
        exit $proc.ExitCode
    } catch {
        Say "Windows didn't allow it, so the Owlmic microphone wasn't set up." "Yellow"
        Finish 1
    }
}

# The Owlmic microphone runs on this virtual cable driver. Its device and endpoint names as Windows reports them.
$DriverDevice = "VB-Audio Virtual Cable"
$OwlmicAudio = "Owlmic Audio"
$EndpointName = "{a45c254e-df1c-4efd-8020-67d146a850e0},2"   # PKEY_Device_DeviceDesc: "CABLE Output"
$AdapterName  = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6"   # PKEY_DeviceInterface_FriendlyName: the part in brackets

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumerator {}

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int EnumAudioEndpoints();
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate();
    int OpenPropertyStore();
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
}

[ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IPolicyConfig {
    int GetMixFormat(); int GetDeviceFormat(); int ResetDeviceFormat(); int SetDeviceFormat();
    int GetProcessingPeriod(); int SetProcessingPeriod(); int GetShareMode(); int SetShareMode();
    int GetPropertyValue(); int SetPropertyValue();
    [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
}

[ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")]
class PolicyConfigClient {}

public static class OwlmicDefaults {
    // flow 0 = speakers, 1 = microphones; role 0 = console, 1 = multimedia, 2 = communications
    public static string Get(int flow, int role) {
        IMMDevice device;
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        if (enumerator.GetDefaultAudioEndpoint(flow, role, out device) != 0 || device == null) return null;
        string id;
        return device.GetId(out id) == 0 ? id : null;
    }
    public static void Set(string id, int role) {
        ((IPolicyConfig)new PolicyConfigClient()).SetDefaultEndpoint(id, role);
    }
}
'@

# 1. Remember the user's default speakers and microphone, for every role.
$saved = @()
foreach ($flow in 0, 1) {
    foreach ($role in 0, 1, 2) {
        $id = [OwlmicDefaults]::Get($flow, $role)
        if ($id) { $saved += [pscustomobject]@{ Id = $id; Role = $role } }
    }
}

# Installing the driver can make it the default device; put the user's own ones back.
function Restore-Defaults {
    foreach ($d in $saved) {
        try { [void][OwlmicDefaults]::Set($d.Id, $d.Role) } catch {}
    }
}

# 2. Install the driver if it isn't there.
function Test-Driver {
    $dev = Get-PnpDevice -Class Media -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -eq $DriverDevice }
    return [bool]($dev | Where-Object { $_.Status -eq "OK" })
}

$restartNeeded = $false
if (Test-Driver) {
    Say "The Owlmic microphone driver is already installed." "Green"
} else {
    $setup = Join-Path $DriverDir "VBCABLE_Setup_x64.exe"
    if (-not (Test-Path $setup)) {
        $tempDriverDir = Join-Path $env:TEMP "owlmic-driver"
        $tempSetup = Join-Path $tempDriverDir "VBCABLE_Setup_x64.exe"
        if (Test-Path $tempSetup) {
            $setup = $tempSetup
            $DriverDir = $tempDriverDir
            Say "Using cached driver in $tempDriverDir" "DarkGray"
        } else {
            Say "Owlmic microphone driver files not found locally. Downloading package..." "Cyan"
            $zip = Join-Path $env:TEMP "VBCABLE_Driver_Pack45.zip"
            $expectedHash = 'B950E39F01AF1D04EA623C8F6D8EB9B6EA5C477C637295FABF20631C85116BFB'
            $sources = @(
                'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip',
                'https://web.archive.org/web/20240901000000id_/https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip'
            )
            $downloaded = $false
            foreach ($url in $sources) {
                try {
                    Say "Connecting to ${url}..." "DarkGray"
                    $prevPref = $ProgressPreference
                    $ProgressPreference = if ($Silent) { 'SilentlyContinue' } else { 'Continue' }
                    Invoke-WebRequest -Uri $url -OutFile $zip -TimeoutSec 60 -UseBasicParsing
                    $ProgressPreference = $prevPref
                    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash
                    if ($hash -eq $expectedHash) {
                        $downloaded = $true
                        Say "Driver download verified successfully (SHA256 OK)." "Green"
                        break
                    } else {
                        Say "Checksum verification mismatch ($hash), attempting fallback mirror..." "Yellow"
                    }
                } catch {
                    Say "Download failed from ${url}: $($_.Exception.Message)" "Yellow"
                }
            }
            if ($downloaded) {
                Say "Extracting driver files..." "Cyan"
                New-Item -ItemType Directory -Path $tempDriverDir -Force | Out-Null
                Expand-Archive -Path $zip -DestinationPath $tempDriverDir -Force
                Remove-Item $zip -Force -ErrorAction SilentlyContinue
                $setup = $tempSetup
                $DriverDir = $tempDriverDir
            } else {
                Restore-Defaults
                Say "Owlmic microphone driver could not be found or downloaded." "Yellow"
                Say "Please check your network connection or reinstall Owlmic." "Yellow"
                Finish 1
            }
        }
    }
    Say "Installing the Owlmic microphone. This can take a minute..."
    $proc = Start-Process -FilePath $setup -ArgumentList "-i", "-h" -WorkingDirectory $DriverDir -PassThru
    if (-not $proc.WaitForExit(180000)) {
        Restore-Defaults
        Say "Installing the Owlmic microphone took too long." "Yellow"
        Finish 1
    }
    # The setup's exit code isn't documented, so check that the driver's device actually appeared.
    # Present devices only: a leftover entry from an old, removed install doesn't count.
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $installed = Get-PnpDevice -Class Media -PresentOnly -ErrorAction SilentlyContinue |
            Where-Object { $_.FriendlyName -eq $DriverDevice }
        if ($installed) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if (-not $installed) {
        Restore-Defaults
        Say "Windows didn't install the Owlmic microphone driver." "Yellow"
        Finish 1
    }
    $restartNeeded = $true
}

# 3. Name it Owlmic. Its endpoints appear shortly after the driver installs.
function Rename-Endpoints([string]$flow, [string]$name) {
    $root = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\$flow"
    $found = 0
    Get-ChildItem -Path $root -ErrorAction SilentlyContinue | ForEach-Object {
        $props = Join-Path $_.PSPath "Properties"
        $values = Get-ItemProperty -Path $props -ErrorAction SilentlyContinue
        if ($values -and ($values.$AdapterName -in @($DriverDevice, $OwlmicAudio))) {
            Set-ItemProperty -Path $props -Name $EndpointName -Value $name -ErrorAction SilentlyContinue
            Set-ItemProperty -Path $props -Name $AdapterName -Value $OwlmicAudio -ErrorAction SilentlyContinue
            $check = Get-ItemProperty -Path $props -ErrorAction SilentlyContinue
            if ($check -and $check.$EndpointName -eq $name) {
                $found++
            }
        }
    }
    return $found
}

function Verify-Endpoint([string]$flow, [string]$expectedName) {
    $root = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\$flow"
    $matched = $false
    Get-ChildItem -Path $root -ErrorAction SilentlyContinue | ForEach-Object {
        $props = Join-Path $_.PSPath "Properties"
        $values = Get-ItemProperty -Path $props -ErrorAction SilentlyContinue
        if ($values -and $values.$EndpointName -eq $expectedName -and $values.$AdapterName -eq $OwlmicAudio) {
            $matched = $true
        }
    }
    return $matched
}

try {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $mics = Rename-Endpoints "Capture" "Owlmic"
        if ($mics -gt 0) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    [void](Rename-Endpoints "Render" "Owlmic Bridge")
} catch {
    Restore-Defaults
    Say "Couldn't name the microphone Owlmic: $($_.Exception.Message)" "Yellow"
    Finish 1
}

if ($mics -eq 0) {
    Restore-Defaults
    if ($restartNeeded) {
        Say "The Owlmic microphone is installed. Restart Windows, then run the Owlmic installer again to finish." "Yellow"
        Finish 3010
    }
    Say "The Owlmic microphone driver is installed, but Windows hasn't created the microphone yet. Restart Windows and try again." "Yellow"
    Finish 1
}

# 4. Apply the names, then put the user's defaults back.
try {
    # AudioEndpointBuilder caches endpoint registry properties. Restarting it forces Windows
    # to reload friendly endpoint names without requiring a system reboot.
    Restart-Service -Name "AudioEndpointBuilder" -Force
    Start-Service -Name "Audiosrv"
    Start-Sleep -Seconds 2
} catch {
    try {
        Restart-Service -Name "Audiosrv" -Force
        Start-Sleep -Seconds 2
    } catch {
        Say "The new name shows after Windows restarts." "Yellow"
    }
}
Restore-Defaults

if (Verify-Endpoint "Capture" "Owlmic") {
    Say "Owlmic is verified and ready. Pick it as the microphone in Meet, Zoom or Teams." "Green"
} else {
    Say "Owlmic is ready. Pick it as the microphone in Meet, Zoom or Teams." "Green"
}
if ($restartNeeded) { Finish 3010 }
Finish 0

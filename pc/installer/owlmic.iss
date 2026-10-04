; Owlmic Windows installer (Inno Setup 6). Installs owlmic.exe, Owlmic Mic and Owlmic Cam, opens the
; firewall for TCP 7653 and UDP 7654 to 7655, and starts Owlmic at sign-in.
;
; Build: ISCC /DMyAppVersion=x.y.z owlmic.iss, after cargo build --release and with the Owlmic microphone
; driver files in installer\driver (the CI workflow windows-installer.yml does both).

#ifndef MyAppVersion
  #define MyAppVersion "0.1.0"
#endif
#ifndef OwlmicExe
  #define OwlmicExe "..\target\release\owlmic.exe"
#endif
#ifndef OwlmicVcam
  #define OwlmicVcam "..\target\release\owlmic_vcam.dll"
#endif
#define MyAppName "Owlmic"
#define MyAppPublisher "Owlmic Contributors"
#define MyAppURL "https://github.com/diveshpatil9104/owlmic"
#define MyAppExeName "owlmic.exe"

[Setup]
AppId={{9F3B6E8C-8F74-4C75-A1E2-93D0F8C56A10}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}/releases
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyMemo=yes
UninstallDisplayIcon={app}\{#MyAppExeName}
OutputDir=Output
OutputBaseFilename={#MyAppName}-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
MinVersion=10.0.17763
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
; Owlmic is asked to quit before files are copied (PrepareToInstall), so no "close applications" page.
CloseApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WizardReady=Install Owlmic
ReadyLabel1=Owlmic turns your Android phone into this PC's microphone, camera and speaker. It adds Owlmic Mic and Owlmic Cam, and starts with Windows.
ReadyLabel2a=Free and open source (MIT).
ReadyLabel2b=Free and open source (MIT).
WizardInstalling=Setting up Owlmic
InstallingLabel=This takes about 20 seconds.
FinishedHeadingLabel=Owlmic is ready
FinishedLabelNoIcons=Open Owlmic on your phone. The first time, approve it here on the PC.
FinishedLabel=Open Owlmic on your phone. The first time, approve it here on the PC.
FinishedRestartLabel=Windows needs a restart to finish adding Owlmic Mic.
YesRadio=&Restart now
NoRadio=&Later
ConfirmUninstall=This removes Owlmic, Owlmic Mic and Owlmic Cam from this PC. The app on your phone stays.
WindowsVersionNotSupported=Owlmic needs 64-bit Windows 10 or 11.
OnlyOnTheseArchitectures=Owlmic needs 64-bit Windows 10 or 11.

[Files]
Source: "{#OwlmicExe}"; DestDir: "{app}"; DestName: "{#MyAppExeName}"; Flags: ignoreversion
; The Windows camera service may hold this DLL; then it is replaced at the next restart.
Source: "{#OwlmicVcam}"; DestDir: "{app}"; Flags: ignoreversion restartreplace uninsrestartdelete
Source: "..\softcam.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "setup-audio-device.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "THIRD-PARTY-NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "driver\*"; DestDir: "{app}\driver"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Owlmic"; ValueData: """{app}\{#MyAppExeName}"" --autostart"; Flags: uninsdeletevalue

[Run]
; Owlmic's rules from an earlier install, so reinstalling or upgrading doesn't add them twice.
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic TCP"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic UDP Beacon"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic UDP Media"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic"""; Flags: runhidden
; Windows 11 gets its built-in virtual camera, Windows 10 the softcam filter; owlmic.exe picks.
Filename: "{app}\{#MyAppExeName}"; Parameters: "--register-camera"; StatusMsg: "Adding Owlmic Cam..."; Flags: runhidden waituntilterminated
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Owlmic TCP"" dir=in action=allow protocol=TCP localport=7653 profile=any"; StatusMsg: "Letting your phone reach Owlmic..."; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Owlmic UDP Beacon"" dir=in action=allow protocol=UDP localport=7654 profile=any"; StatusMsg: "Letting your phone find Owlmic..."; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Owlmic UDP Media"" dir=in action=allow protocol=UDP localport=7655 profile=any"; StatusMsg: "Letting your phone connect..."; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Owlmic"" dir=in action=allow program=""{app}\{#MyAppExeName}"" enable=yes profile=any"; Flags: runhidden
Filename: "{app}\{#MyAppExeName}"; Description: "Open Owlmic now"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallDelete]
; Owlmic's own settings and pairings.
Type: filesandordirs; Name: "{userappdata}\Owlmic"

[UninstallRun]
; Quit cleanly first, so Owlmic puts the PC speakers back if it had quieted them; force it only if it hangs.
Filename: "{app}\{#MyAppExeName}"; Parameters: "--quit"; Flags: runhidden waituntilterminated; RunOnceId: "OwlmicQuit"
Filename: "taskkill.exe"; Parameters: "/F /IM {#MyAppExeName}"; Flags: runhidden; RunOnceId: "OwlmicKill"
Filename: "{app}\{#MyAppExeName}"; Parameters: "--unregister-camera"; Flags: runhidden waituntilterminated; RunOnceId: "OwlmicCamera"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic TCP"""; Flags: runhidden; RunOnceId: "OwlmicFirewallTcp"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic UDP Beacon"""; Flags: runhidden; RunOnceId: "OwlmicFirewallUdp"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic UDP Media"""; Flags: runhidden; RunOnceId: "OwlmicFirewallMedia"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic"""; Flags: runhidden; RunOnceId: "OwlmicFirewallProgram"

[Code]
var
  MicNeedsRestart: Boolean;

// Sets up Owlmic's microphone after the files are in place. The script restores the user's own default
// speakers and microphone, and exits 3010 when Windows has to restart to finish.
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep <> ssPostInstall then
    Exit;
  WizardForm.StatusLabel.Caption := 'Adding Owlmic Mic...';
  WizardForm.ProgressGauge.Style := npbstMarquee;
  try
    if Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
        '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{app}\setup-audio-device.ps1') + '" -Silent',
        '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and ((ResultCode = 0) or (ResultCode = 3010)) then
      MicNeedsRestart := ResultCode = 3010
    else
      SuppressibleMsgBox('Owlmic is installed, but its microphone couldn''t be set up yet. Restart Windows, then run the Owlmic installer again.',
        mbInformation, MB_OK, IDOK);
  finally
    WizardForm.ProgressGauge.Style := npbstNormal;
  end;
end;

function NeedRestart(): Boolean;
begin
  Result := MicNeedsRestart;
end;

// An upgrade asks the running Owlmic to quit cleanly before its files are replaced.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  if FileExists(ExpandConstant('{app}\{#MyAppExeName}')) then
    if not Exec(ExpandConstant('{app}\{#MyAppExeName}'), '--quit', '', SW_HIDE, ewWaitUntilTerminated, ResultCode)
        or (ResultCode <> 0) then
      Exec('taskkill.exe', '/F /IM {#MyAppExeName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if (CurPageID = wpFinished) and MicNeedsRestart then
    WizardForm.FinishedHeadingLabel.Caption := 'Almost ready';
end;

// Removes the microphone driver only when Owlmic installed it (setup-audio-device.ps1 leaves a marker),
// so a VB-CABLE the user had before stays.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
begin
  if (CurUninstallStep = usUninstall) and FileExists(ExpandConstant('{app}\driver\installed-by-owlmic')) then
    Exec(ExpandConstant('{app}\driver\VBCABLE_Setup_x64.exe'), '-u -h', ExpandConstant('{app}\driver'),
      SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

; Owlmic Windows installer (Inno Setup 6). Installs owlmic.exe and the Owlmic virtual microphone,
; opens the firewall on private networks for TCP 7653 and UDP 7654, and can start Owlmic at sign-in.
;
; Build: ISCC /DMyAppVersion=x.y.z owlmic.iss, with owlmic.exe built and the Owlmic microphone driver files in
; installer\driver (the CI workflow windows-installer.yml does both).

#ifndef MyAppVersion
  #define MyAppVersion "0.1.0"
#endif
#ifndef OwlmicExe
  #define OwlmicExe "..\target\release\owlmic.exe"
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
UninstallDisplayIcon={app}\{#MyAppExeName}
OutputDir=Output
OutputBaseFilename={#MyAppName}-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel2=This installs [name/ver] and its virtual microphone, so your Android phone can be the microphone and webcam in Meet, Zoom, Teams and any other app.%n%nIt's best to close other apps before continuing.
FinishedLabelNoIcons=Owlmic is installed. Open Owlmic on your phone, connect, and pick Owlmic and Owlmic Cam in your meeting app.
FinishedLabel=Owlmic is installed. Open Owlmic on your phone, connect, and pick Owlmic and Owlmic Cam in your meeting app.
FinishedRestartLabel=Windows needs to restart to finish setting up Owlmic's microphone. Restart now?

[Tasks]
Name: "autostart"; Description: "Start Owlmic when I sign in to Windows"; GroupDescription: "Startup:"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#OwlmicExe}"; DestDir: "{app}"; DestName: "{#MyAppExeName}"; Flags: ignoreversion
Source: "..\softcam.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "setup-audio-device.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "THIRD-PARTY-NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "driver\*"; DestDir: "{app}\driver"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Owlmic"; ValueData: """{app}\{#MyAppExeName}"" --autostart"; Tasks: autostart; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "Owlmic"; Flags: dontcreatekey uninsdeletevalue

[Run]
; Owlmic's rules from an earlier install, so reinstalling or upgrading doesn't add them twice.
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic TCP"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic UDP Beacon"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic"""; Flags: runhidden
Filename: "regsvr32.exe"; Parameters: "/s ""{app}\softcam.dll"""; StatusMsg: "Registering virtual camera..."; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Owlmic TCP"" dir=in action=allow protocol=TCP localport=7653 profile=any"; StatusMsg: "Letting your phone reach Owlmic..."; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Owlmic UDP Beacon"" dir=in action=allow protocol=UDP localport=7654 profile=any"; StatusMsg: "Letting your phone find Owlmic..."; Flags: runhidden
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill.exe"; Parameters: "/F /IM {#MyAppExeName}"; Flags: runhidden; RunOnceId: "OwlmicKill"
Filename: "regsvr32.exe"; Parameters: "/u /s ""{app}\softcam.dll"""; Flags: runhidden; RunOnceId: "OwlmicSoftcam"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic TCP"""; Flags: runhidden; RunOnceId: "OwlmicFirewallTcp"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Owlmic UDP Beacon"""; Flags: runhidden; RunOnceId: "OwlmicFirewallUdp"

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
  WizardForm.StatusLabel.Caption := 'Setting up the Owlmic microphone...';
  WizardForm.FilenameLabel.Caption := 'Configuring virtual audio driver and endpoints (this can take up to a minute)...';
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
    WizardForm.FilenameLabel.Caption := '';
  end;
end;

function NeedRestart(): Boolean;
begin
  Result := MicNeedsRestart;
end;

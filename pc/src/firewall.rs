//! Windows Firewall rule inspection and automated elevation.
//!
//! Owlmic listens on 0.0.0.0:7653 (TCP) and 0.0.0.0:7654 (UDP). Windows Firewall
//! drops inbound packets by default for apps without rules. This module checks for
//! existing rules and invokes an elevated UAC helper via ShellExecuteExW if missing.

use std::io;

pub const TCP_RULE_NAME: &str = "Owlmic TCP";
pub const UDP_RULE_NAME: &str = "Owlmic UDP Beacon";
pub const TCP_PORT: u16 = 7653;
pub const UDP_PORT: u16 = 7654;

/// Generates the standard netsh command to add an inbound firewall rule for all network profiles.
pub fn format_add_rule_cmd(name: &str, protocol: &str, port: u16) -> String {
    format!(
        "netsh advfirewall firewall add rule name=\"{}\" dir=in action=allow protocol={} localport={} profile=any",
        name, protocol, port
    )
}

/// Parses the output of `netsh advfirewall firewall show rule` to determine if a rule is present,
/// active, and enables inbound traffic on public networks.
pub fn parse_netsh_output(stdout: &str, rule_name: &str) -> bool {
    let lower = stdout.to_lowercase();
    if lower.contains("no rules match") {
        return false;
    }
    if !lower.contains(&rule_name.to_lowercase()) {
        return false;
    }
    if lower.contains("enabled:                              no")
        || lower.contains("action:                               block")
    {
        return false;
    }
    // Must include public profile support. Windows often classifies home Wi-Fi and mobile hotspots as Public networks.
    if !lower.contains("public") {
        return false;
    }
    true
}

fn check_rule_present(name: &str) -> bool {
    use std::os::windows::process::CommandExt;
    use std::process::Command;

    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let output = Command::new("netsh")
        .args([
            "advfirewall",
            "firewall",
            "show",
            "rule",
            &format!("name={}", name),
            "verbose",
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    match output {
        Ok(out) => {
            if !out.status.success() {
                return false;
            }
            let text = String::from_utf8_lossy(&out.stdout);
            parse_netsh_output(&text, name)
        }
        Err(_) => false,
    }
}

/// Checks whether inbound rules for TCP :7653 and UDP :7654 exist and cover public/private networks.
pub fn check_rules() -> (bool, bool) {
    (
        check_rule_present(TCP_RULE_NAME),
        check_rule_present(UDP_RULE_NAME),
    )
}

/// Returns true if both TCP and UDP rules are allowed in the firewall.
pub fn is_firewall_allowed() -> bool {
    let (tcp, udp) = check_rules();
    tcp && udp
}

/// Prompts for UAC elevation to add firewall rules for TCP :7653 and UDP :7654 across all profiles.
/// Also purges any stale or conflicting block rules created by Windows Defender for the current executable.
/// Executes in a single hidden elevated cmd.exe process so the user only sees one UAC dialog.
pub fn ensure_rules_elevated() -> io::Result<()> {
    use std::ffi::c_void;

    #[repr(C)]
    struct ShellExecuteInfoW {
        cb_size: u32,
        f_mask: u32,
        hwnd: usize,
        lp_verb: *const u16,
        lp_file: *const u16,
        lp_parameters: *const u16,
        lp_directory: *const u16,
        n_show: i32,
        h_inst_app: usize,
        lp_id_list: *const c_void,
        lp_class: *const u16,
        h_key_class: usize,
        dw_hot_key: u32,
        h_icon_or_monitor: usize,
        h_process: usize,
    }

    #[link(name = "shell32")]
    extern "system" {
        fn ShellExecuteExW(pExecInfo: *mut ShellExecuteInfoW) -> i32;
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn WaitForSingleObject(hHandle: usize, dwMilliseconds: u32) -> u32;
        fn CloseHandle(hObject: usize) -> i32;
    }

    const SEE_MASK_NOCLOSEPROCESS: u32 = 0x00000040;
    const SW_HIDE: i32 = 0;

    let exe_path = std::env::current_exe().unwrap_or_default();
    let exe_str = exe_path.to_string_lossy();

    let tcp_cmd = format_add_rule_cmd(TCP_RULE_NAME, "TCP", TCP_PORT);
    let udp_cmd = format_add_rule_cmd(UDP_RULE_NAME, "UDP", UDP_PORT);

    let del_prog = format!(
        "netsh advfirewall firewall delete rule name=all program=\"{}\"",
        exe_str
    );
    // Also by name: a rule left by an install in another folder doesn't match this exe's path.
    let del_old = "netsh advfirewall firewall delete rule name=\"Owlmic\"";
    let del_tcp = format!(
        "netsh advfirewall firewall delete rule name=\"{}\"",
        TCP_RULE_NAME
    );
    let del_udp = format!(
        "netsh advfirewall firewall delete rule name=\"{}\"",
        UDP_RULE_NAME
    );
    let add_prog = format!("netsh advfirewall firewall add rule name=\"Owlmic\" dir=in action=allow program=\"{}\" enable=yes profile=any", exe_str);

    let full_args = format!(
        "/c \"{} & {} & {} & {} & {} & {} & {}\"",
        del_prog, del_old, del_tcp, del_udp, tcp_cmd, udp_cmd, add_prog
    );

    let verb: Vec<u16> = "runas".encode_utf16().chain(std::iter::once(0)).collect();
    let file: Vec<u16> = "cmd.exe".encode_utf16().chain(std::iter::once(0)).collect();
    let params: Vec<u16> = full_args.encode_utf16().chain(std::iter::once(0)).collect();

    let mut info: ShellExecuteInfoW = unsafe { std::mem::zeroed() };
    info.cb_size = std::mem::size_of::<ShellExecuteInfoW>() as u32;
    info.f_mask = SEE_MASK_NOCLOSEPROCESS;
    info.lp_verb = verb.as_ptr();
    info.lp_file = file.as_ptr();
    info.lp_parameters = params.as_ptr();
    info.n_show = SW_HIDE;

    let res = unsafe { ShellExecuteExW(&mut info) };
    if res == 0 {
        return Err(io::Error::last_os_error());
    }

    if info.h_process != 0 {
        unsafe {
            WaitForSingleObject(info.h_process, 30_000);
            CloseHandle(info.h_process);
        }
    }

    let (tcp, udp) = check_rules();
    if tcp && udp {
        Ok(())
    } else {
        Err(io::Error::other(
            "Firewall rules were not added after elevation",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_netsh_output_present() {
        let stdout = "
Rule Name:                            Owlmic TCP
----------------------------------------------------------------------
Enabled:                              Yes
Direction:                            In
Profiles:                             Domain,Private,Public
Grouping:
LocalIP:                              Any
RemoteIP:                             Any
Protocol:                             TCP
LocalPort:                            7653
RemotePort:                           Any
Edge traversal:                       No
Action:                               Allow
Ok.
";
        assert!(parse_netsh_output(stdout, "Owlmic TCP"));
        assert!(!parse_netsh_output(stdout, "Owlmic UDP Beacon"));
    }

    #[test]
    fn test_parse_netsh_output_private_only_rejected() {
        let stdout = "
Rule Name:                            Owlmic TCP
----------------------------------------------------------------------
Enabled:                              Yes
Direction:                            In
Profiles:                             Private
Action:                               Allow
Ok.
";
        assert!(!parse_netsh_output(stdout, "Owlmic TCP"));
    }

    #[test]
    fn test_parse_netsh_output_missing() {
        let stdout = "\nNo rules match the specified criteria.\n";
        assert!(!parse_netsh_output(stdout, "Owlmic TCP"));
        assert!(!parse_netsh_output(stdout, "Owlmic UDP Beacon"));
        assert!(!parse_netsh_output("", "Owlmic TCP"));
    }

    #[test]
    fn test_command_arguments_format() {
        let tcp_cmd = format_add_rule_cmd(TCP_RULE_NAME, "TCP", TCP_PORT);
        assert!(tcp_cmd.contains("name=\"Owlmic TCP\""));
        assert!(tcp_cmd.contains("protocol=TCP"));
        assert!(tcp_cmd.contains("localport=7653"));
        assert!(tcp_cmd.contains("dir=in action=allow"));
        assert!(tcp_cmd.contains("profile=any"));

        let udp_cmd = format_add_rule_cmd(UDP_RULE_NAME, "UDP", UDP_PORT);
        assert!(udp_cmd.contains("name=\"Owlmic UDP Beacon\""));
        assert!(udp_cmd.contains("protocol=UDP"));
        assert!(udp_cmd.contains("localport=7654"));
        assert!(udp_cmd.contains("profile=any"));
    }
}

//! The firewall rules Owlmic needs (SYSTEM_DESIGN section 24.2, step 6), named exactly as the
//! installer names them, so a repair replaces rather than duplicates them.

pub const PROGRAM_RULE: &str = "Owlmic";

/// Name, protocol and port of each inbound port rule.
pub const PORT_RULES: [(&str, &str, u16); 3] = [
    ("Owlmic TCP", "TCP", 7653),
    ("Owlmic UDP Beacon", "UDP", 7654),
    ("Owlmic UDP Media", "UDP", 7655),
];

/// The netsh commands that remove earlier rules and add the current ones.
pub fn commands(exe: &str) -> Vec<String> {
    let mut out: Vec<String> = PORT_RULES
        .iter()
        .map(|(name, _, _)| name)
        .chain([&PROGRAM_RULE])
        .map(|name| format!("netsh advfirewall firewall delete rule name=\"{name}\""))
        .collect();
    out.extend(PORT_RULES.iter().map(|(name, protocol, port)| {
        format!("netsh advfirewall firewall add rule name=\"{name}\" dir=in action=allow protocol={protocol} localport={port} profile=any")
    }));
    out.push(format!(
        "netsh advfirewall firewall add rule name=\"{PROGRAM_RULE}\" dir=in action=allow program=\"{exe}\" enable=yes profile=any"
    ));
    out
}

/// Whether `netsh advfirewall firewall show rule name=... verbose` shows the rule enabled,
/// allowing, and covering public networks (Windows often treats home Wi-Fi as public).
pub fn rule_allows(netsh_output: &str, name: &str) -> bool {
    let text = netsh_output.to_lowercase();
    let field = |key: &str| {
        text.lines()
            .find_map(|l| {
                l.trim()
                    .strip_prefix(key)
                    .map(|v| v.trim_start_matches(':').trim().to_owned())
            })
            .unwrap_or_default()
    };
    text.contains(&name.to_lowercase())
        && field("enabled") == "yes"
        && field("action") == "allow"
        && field("profiles").contains("public")
}

#[cfg(windows)]
pub fn rules_present() -> bool {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    PORT_RULES.iter().all(|(name, _, _)| {
        std::process::Command::new("netsh")
            .args([
                "advfirewall",
                "firewall",
                "show",
                "rule",
                &format!("name={name}"),
                "verbose",
            ])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .is_ok_and(|o| {
                o.status.success() && rule_allows(&String::from_utf8_lossy(&o.stdout), name)
            })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHOWN: &str = "
Rule Name:                            Owlmic TCP
----------------------------------------------------------------------
Enabled:                              Yes
Direction:                            In
Profiles:                             Domain,Private,Public
Protocol:                             TCP
LocalPort:                            7653
Action:                               Allow
Ok.
";

    #[test]
    fn a_rule_must_be_enabled_allowing_and_cover_public_networks() {
        assert!(rule_allows(SHOWN, "Owlmic TCP"));
        assert!(!rule_allows(SHOWN, "Owlmic UDP Beacon"));
        assert!(!rule_allows(
            &SHOWN.replace("Domain,Private,Public", "Private"),
            "Owlmic TCP"
        ));
        assert!(!rule_allows(&SHOWN.replace("Allow", "Block"), "Owlmic TCP"));
        assert!(!rule_allows(
            "\nNo rules match the specified criteria.\n",
            "Owlmic TCP"
        ));
    }

    #[test]
    fn commands_replace_every_rule_by_its_exact_name() {
        let c = commands("C:\\Program Files\\Owlmic\\owlmic.exe");
        assert_eq!(c.len(), 8);
        assert!(c[0].ends_with("name=\"Owlmic TCP\""));
        assert!(c[3].ends_with("name=\"Owlmic\""));
        assert!(c.iter().any(|l| l.contains("name=\"Owlmic UDP Media\"")
            && l.contains("protocol=UDP localport=7655")));
        assert!(c[7].contains("program=\"C:\\Program Files\\Owlmic\\owlmic.exe\""));
    }
}

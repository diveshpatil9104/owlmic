# Security Policy

## Reporting a Vulnerability

**Please do NOT open a public issue for security vulnerabilities.**

If you discover a security vulnerability in Owlmic, please report it responsibly:

1. **Email:** Send a detailed report to **diveshpatil9104@gmail.com**
2. **Subject line:** `[SECURITY] Brief description of the vulnerability`
3. **Include:**
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

## Response Timeline

- **Acknowledgment:** Within 48 hours of your report
- **Assessment:** Within 7 days, we'll provide an initial assessment
- **Fix:** We aim to release a fix within 30 days for confirmed vulnerabilities
- **Disclosure:** We'll coordinate disclosure timing with you

## Scope

The following are in scope for security reports:

| Component | Examples |
|-----------|---------|
| Protocol | Frame injection, buffer overflows, malformed packet handling |
| Pairing | Key leakage, authentication bypass, unauthorized device access |
| PC binary | Privilege escalation, arbitrary code execution, DLL injection |
| Android app | Permission bypass, data leakage, intent hijacking |
| Network | Discovery beacon spoofing, man-in-the-middle on local network |

### Out of Scope

- Denial of service on the local network (Owlmic is local-only by design)
- Social engineering attacks
- Vulnerabilities in dependencies - report these to the upstream project, but let us know so we can update
- Physical access attacks (if someone has physical access to your PC, Owlmic's security is the least of your concerns)

## Security Design

- **Local only:** traffic stays on the cable or the local network between your phone and PC. No cloud relay, no internet-facing server.
- **Approve once:** a new phone must be approved on the PC before it can connect.
- **No tracking:** no accounts, analytics, telemetry or logs.
- **Off by default:** the mic, camera and speaker start only when you turn them on.

## Supported Versions

| Version | Status |
|---------|--------|
| Latest release | Supported |
| Previous release | Supported (security fixes only) |
| Older versions | Unsupported |

## Recognition

We appreciate responsible disclosure. Security reporters will be credited in the release notes (unless they prefer to remain anonymous).

---

*Thank you for helping keep Owlmic and its users safe.*

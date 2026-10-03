# Contributing to Owlmic

Thanks for helping. Owlmic is being rebuilt from the ground up, so this is a good time to join: small, focused pull requests are the best way in.

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Report a bug:** [open a bug report](https://github.com/diveshpatil9104/owlmic/issues/new?template=bug_report.yml) with steps to reproduce, your phone and Windows version, and how they were connected.
- **Suggest a feature:** [open a feature request](https://github.com/diveshpatil9104/owlmic/issues/new?template=feature_request.yml).
- **Pick an issue:** look for [`good first issue`](https://github.com/diveshpatil9104/owlmic/labels/good%20first%20issue) and say in the issue that you are taking it.
- **Test on your devices:** different phones and PCs find different problems.

## Setup

```bash
git clone https://github.com/<your-username>/owlmic.git
cd owlmic
git submodule update --init
```

### Windows app (`pc/`)

Needs Windows 10 or 11, [Rust](https://rustup.rs/) stable and the Visual C++ Build Tools.

```powershell
cd pc
cargo build
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

Tests that change real system state (the registry, audio devices) only run when asked: `cargo test -- --ignored`.

### Android app (`android/`)

Needs Android Studio (or the Android SDK), the NDK and CMake.

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Making a change

1. **Branch from `main`:** `<type>/<short-description>`, for example `fix/mic-restart` or `feat/speaker-mode`.
2. **Keep it small:** one topic per pull request.
3. **Stay lean:** no new dependency without asking first, no speculative code, comments only for the non-obvious why.
4. **No binaries in git:** `.apk`, `.exe`, `.dll`, `.so` and `.zip` files belong in releases, not the repository.
5. **Check before pushing:** the commands above pass with no warnings.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/), lowercase and imperative:

```
<type>(<scope>): <short summary>

<why the change was needed>
```

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `chore`. Scopes: `android`, `pc`, `installer`, `ci`.

Examples: `fix(pc): restart the mic when its stream stalls`, `feat(android): add the speaker tile`.

## Pull requests

- Fill in the template: what changed, why, and how you tested it.
- UI changes need a screenshot or a short recording; audio and video changes need a note on what you tested and on which devices.
- CI must be green. A maintainer reviews every pull request.

## Getting help

Ask in [Discussions](https://github.com/diveshpatil9104/owlmic/discussions).

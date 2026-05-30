# ZDT-D Copilot Instructions

You are working on ZDT-D as a conservative maintainer and optimizer.

The goal is to polish the project without changing the original idea, architecture, or runtime behavior.

## Core rules

- Preserve the existing project purpose and behavior.
- Make only small, safe, incremental improvements.
- Prefer readability, error handling, safer shell quoting, small helper extraction, comments for complex logic, and tiny tests when obvious.
- Do not perform mass formatting.
- Do not rewrite architecture.
- Do not remove features.
- Do not rename packages, modules, API routes, binaries, workflows, or public configuration keys unless the task explicitly asks for it.
- Do not add telemetry, analytics, tracking, or new external network calls.
- Do not touch secrets, keystores, certificates, signing configuration, or private keys.

## Validation rules

- Do not use `build.sh` for AI validation. It is for local Termux builds.
- The GitHub validation source of truth is `.github/workflows/build.yml`.
- AI changes must be validated by dispatching `build.yml` with `build_type=Release` on the `copilot-polish` branch.
- If validation fails, fix only the compilation or packaging error shown in the build log.
- If the failure cannot be fixed with a small safe patch, revert the AI diff.

## Protected ZDT-D behavior

Do not change these areas unless the user explicitly requests it:

- release workflow behavior
- service-build release body/tag logic
- stable release tag logic
- version/versionCode policy
- app assignment conflict rules
- MyProxy routing behavior
- T2S priority mode behavior
- iptables / nftables / NFQUEUE behavior
- VPN/netd routing behavior
- Zygisk behavior
- Magisk service boot behavior
- module packaging behavior

## High-risk paths

Treat these paths as read-only during automatic polish tasks:

- `.github/workflows/build.yml`
- `.github/workflows/*release*`
- `build.sh`
- `module.prop`
- `module_template/service.sh`
- `module_template/customize.sh`
- `module_template/uninstall.sh`
- `prebuilt/**`
- `keystores/**`
- `zygisk/**`
- `rust/zdtd/src/api.rs`
- `rust/zdtd/src/runtime.rs`
- `rust/zdtd/src/vpn_netd.rs`
- `rust/zdtd/src/iptables/**`

## Preferred safe areas

Automatic polish may focus on:

- Android UI files under `application/app/src/main/java/com/android/zdtd/service/ui/`
- non-routing diagnostics/helper Kotlin files
- `rust/dpi-detector/**`
- `rust/nfqws-tester/**`
- docs and comments
- small scripts created specifically for AI automation

## Commit style

Use clear English commit messages, for example:

- `AI polish(ui): simplify app selection helper`
- `AI polish(rust): improve detector error context`
- `AI polish(docs): clarify Copilot validation flow`

Commit body should explain:

- what changed
- why it is safe
- what validation was run

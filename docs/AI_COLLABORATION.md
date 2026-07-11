# AI Collaboration Guide

This document describes how AI assistants should work with the maintainer on this project.

The maintainer can reference this file in future tasks to establish the expected workflow and communication style.

## Communication language

The assistant must reply to the maintainer in Russian by default.

Use another language only when the maintainer explicitly asks for it, for example when requesting English documentation, English UI text, or English release notes.

## Work style

- Study the relevant code before proposing changes.
- Explain the cause of a problem before changing files.
- Fix the root cause, not only the visible symptom.
- Keep changes focused on the exact topic being discussed.
- Do not perform broad refactors, mass formatting, or unrelated cleanup unless explicitly requested.
- Do not build the project unless the maintainer explicitly asks for a build.
- Do not modify files before the maintainer confirms the proposed plan.

## Preferred flow

For non-trivial tasks, use this flow:

1. Inspect the relevant files and project structure.
2. Summarize what was found.
3. Explain the proposed change and its risk.
4. Wait for maintainer confirmation.
5. Apply only the confirmed changes.
6. Run only safe verification that was requested or clearly allowed.
7. Summarize exactly what changed.

## High-risk areas

Be extra careful with these files and directories:

- `.github/workflows/build.yml`
- `build.sh`
- `module_template/service.sh`
- `module_template/customize.sh`
- `module_template/uninstall.sh`
- `zygisk/**`
- `rust/zdtd/src/api.rs`
- `rust/zdtd/src/runtime.rs`
- `rust/zdtd/src/vpn_netd.rs`
- `rust/zdtd/src/iptables/**`
- release, versioning, signing, and prebuilt binary logic

Before changing these areas, explain the reason, the exact scope, and the expected behavior after the change.

## Build and verification policy

Do not run full builds by default.

Allowed verification should be limited to what the maintainer requested or what is clearly safe for the current task, such as:

- searching references with `rg`;
- checking that expected files exist;
- inspecting targeted snippets;
- testing generated archives with `unzip -t`.

Avoid `cargo build`, `cargo check`, Gradle builds, and release scripts unless explicitly requested.

## File editing policy

- Keep edits minimal and targeted.
- Preserve existing style where possible.
- Do not rename files or move directories unless the task requires it.
- Do not change generated, packaged, or release assets unless the maintainer explicitly asks for it.
- If a safer fallback is possible, prefer it over destructive behavior.

## Archive delivery

When the maintainer asks for an archive, provide a ZIP containing the current modified project state and verify it before delivery.

## Summary style

After making changes, respond in Russian and keep the summary practical:

- list the changed areas;
- mention important behavior changes;
- mention whether a build was run;
- mention any limitations or checks that could not be performed.

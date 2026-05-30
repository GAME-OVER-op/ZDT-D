# Copilot Polish Automation for ZDT-D

This document describes the safe AI-polish workflow for ZDT-D.

## Goal

The goal is not to let AI rewrite the project. The goal is to let Copilot make small, conservative, behavior-preserving improvements in a separate branch:

```text
copilot-polish
```

The `main` branch must remain the release/source-of-truth branch.

## What was added

```text
.github/copilot-instructions.md
.github/workflows/ai-polish.yml
AGENTS.md
scripts/ai-polish/select-target.sh
scripts/ai-polish/run-copilot-polish.sh
scripts/ai-polish/repair-from-build-log.sh
scripts/ai-polish/dispatch-build.sh
scripts/ai-polish/guard-diff.sh
scripts/ai-polish/commit-if-changed.sh
docs/COPILOT_POLISH.md
```

## Required GitHub secrets

Add these repository secrets in GitHub:

```text
COPILOT_GITHUB_TOKEN
AI_WORKFLOW_TOKEN
```

### COPILOT_GITHUB_TOKEN

A fine-grained personal access token for a GitHub account with an active Copilot license.
It must have the `Copilot Requests` permission.

### AI_WORKFLOW_TOKEN

A token that can:

```text
push to copilot-polish
run GitHub Actions workflows
read workflow runs/logs
```

Using a PAT or GitHub App token is preferred when one workflow needs to dispatch another workflow.

## Why build.sh is not used

`build.sh` is for local Termux builds.

The AI workflow must validate changes with the real GitHub workflow:

```text
.github/workflows/build.yml
```

The dispatch command used by the scripts is equivalent to:

```bash
gh workflow run build.yml --ref copilot-polish -f build_type=Release
```

## How the workflow works

```text
1. ai-polish.yml starts from main.
2. It checks out or creates the copilot-polish branch.
3. It selects one safe target file.
4. Copilot inspects that file and local dependencies.
5. Copilot makes at most one small safe improvement.
6. guard-diff.sh rejects risky or oversized diffs.
7. The change is committed to copilot-polish.
8. build.yml is dispatched on copilot-polish with build_type=Release.
9. If build.yml passes, the commit stays.
10. If build.yml fails, Copilot gets build.log and can make a minimal repair.
11. If the repaired build passes, the repair commit stays.
12. If validation still fails, the workflow fails and manual review is required.
```

## Safe random target selection

The selector is intentionally conservative. It focuses on safer areas:

```text
application/app/src/main/java/com/android/zdtd/service/ui/**
application/app/src/main/java/com/android/zdtd/service/diagnostics/**
rust/dpi-detector/**
rust/nfqws-tester/**
docs/**
README.md
```

Protected areas are blocked by `guard-diff.sh`.

## Protected areas

Automatic AI polish must not touch:

```text
.github/workflows/build.yml
build.sh
module.prop
module_template/service.sh
module_template/customize.sh
module_template/uninstall.sh
prebuilt/**
keystores/**
zygisk/**
rust/zdtd/src/api.rs
rust/zdtd/src/runtime.rs
rust/zdtd/src/vpn_netd.rs
rust/zdtd/src/iptables/**
```

These areas are core architecture, release logic, routing, native injection, packaging, or sensitive files.

## Manual setup commands

Run once locally if the branch does not exist yet:

```bash
cd ~/ZDT-D

git fetch origin

git checkout main
git pull --ff-only origin main

git checkout -B copilot-polish origin/main
git push -u origin copilot-polish

git checkout main
```

## Manual workflow run

From a local machine with GitHub CLI:

```bash
cd ~/ZDT-D

gh workflow run ai-polish.yml \
  --ref main \
  -f target_branch=copilot-polish \
  -f max_repair_attempts=1
```

Run on a specific file:

```bash
gh workflow run ai-polish.yml \
  --ref main \
  -f target_branch=copilot-polish \
  -f target_file='application/app/src/main/java/com/android/zdtd/service/ui/ProxyInfoUi.kt' \
  -f max_repair_attempts=1
```

## Local Copilot test

Only for testing the prompt locally. This does not replace GitHub validation.

```bash
cd ~/ZDT-D
npm install -g @github/copilot
export COPILOT_GITHUB_TOKEN='YOUR_TOKEN'

scripts/ai-polish/select-target.sh
scripts/ai-polish/run-copilot-polish.sh
scripts/ai-polish/guard-diff.sh
```

Do not use `build.sh` as the final AI validation step.

## Review flow

After several good commits accumulate in `copilot-polish`, open a PR:

```text
copilot-polish -> main
```

Review the diff manually before merging.

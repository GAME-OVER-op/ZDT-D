# Agent Rules for ZDT-D

This repository may be processed by AI agents. Agents must follow `.github/copilot-instructions.md`.

Short version:

- Do not change the project idea or architecture.
- Do not touch release, service-build, routing, NFQUEUE, Zygisk, or module boot logic unless explicitly requested.
- Do not use `build.sh` for CI validation.
- Validate AI changes with `.github/workflows/build.yml` on the `copilot-polish` branch.
- Keep diffs small, safe, and easy to review.

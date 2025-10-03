# Version 1.5.5  
**Stable Release**

## What's New
- Many bug fixes; the codebase is undergoing gradual refactoring and cleanup to improve maintainability and reliability.
- Web page code cleaned and optimized — faster loading and improved stability.
- Added **Opera-proxy** program to the module, providing additional options to bypass regional blocks and improve access freedom.
- Updated Bye DPI binaries from **16.6 → 17.3** and refreshed Bye DPI strategy.
- New configuration variable: **demon** (service-related setting).
- Replaced the previous Python traffic redirection tool with **T2S** — a transparent→SOCKS5 forwarder. T2S appears in the Monitoring section for real-time observation.
- Backup lists extended to include new application/program list files.
- Added a test command to temporarily **disable captive-portal detection while the service is running**; captive-portal is **re-enabled when the service stops** to allow network authorization where required.
- Performance optimizations and other stability improvements.

## Notes & Compatibility
- If the module reports incompatibility on your device, it does not necessarily mean it will not run — it may operate with limitations. This is intended to protect your device from bootloops and issues when required system utilities are missing.
- **Author's "block" version:** this variant aggressively blocks ads, trackers and certain services (including some websites and apps). Use it only if you understand and accept these restrictions.

**Recommended:** Restart your device after the update to ensure iptables rules, services and web UI assets are applied correctly.

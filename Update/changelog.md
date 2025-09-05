# Version 1.6.0  
**Stable Release**

## What's New

- **Fixed traffic accounting bug**  
  Fixed an issue in traffic accounting: counters now report correctly across all supported platforms. The fixes address integer overflow / sign errors and aggregation mistakes that could lead to incorrect totals.

- **Complete web interface redesign**  
  Full redesign of the web interface: pages and navigation were reworked, status and log readability improved. The UI is now more modern and user-friendly — minor visual adjustments may still occur depending on the browser.

- **Changed iptables logic (nat table)**  
  The logic for building rules in the `nat` table has been revised: the order and types of applied rules were reviewed to ensure correct DNAT/REDIRECT behavior, reduce conflicts, and prevent accidental redirection of traffic system-wide.

- **DPI tunnel configuration changes; zapret for Telegram**  
  DPI tunnel settings updated — new/adjusted parameters for traffic capture (port/protocol/mode). A dedicated `zapret` configuration for Telegram was added to improve call quality and connection reliability under certain network conditions.

- **Other tweaks — optimizations and stability**  
  Miscellaneous fixes: script optimizations, improved service stability on start/stop, small bug fixes and overall stability improvements across different environments.

---

**Note:** It is recommended to restart the device after installing this update to ensure all changes (iptables rules, services and web UI assets) are fully applied.

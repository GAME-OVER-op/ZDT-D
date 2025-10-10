# Version 1.6.8  
**Stable Release**

## What's New

- **Battery consumption fix**  
  Resolved an issue that caused elevated device battery drain.

- **Improved battery-saving script**  
  The power-saving script has been enhanced to better manage module services and reduce energy usage.

- **Power-saving behavior (toggle-controlled)**  
  - When the power-saving toggle is enabled and the screen is **off for 30 minutes**, module services are **frozen** to save battery.  
  - When the screen is turned back on, services are **resumed**; wake-up time depends on the scenario (typically up to **30 seconds**).

- **Additional fixes and optimizations**  
  Miscellaneous bug fixes and stability improvements related to power management and service handling.

**Note:** Recommended to restart the device after installing to ensure updated service scheduling and power-management scripts are applied.

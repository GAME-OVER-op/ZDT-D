# Version 1.6.6  
**Stable Release**

## What's New
- **Code cleanup & optimizations**  
  Major cleanup of the core code structure and optimizations to improve maintainability and runtime performance.

- **Fixed zapret multiple-start bug**  
  Fixed a critical issue where, under certain conditions, zapret was started 6 times instead of the single expected instance. The logic is corrected — now only **one** zapret instance starts when conditions require it.

- **Known issues**  
  There may still be unaccounted-for edge cases and minor bugs. They will be addressed in upcoming releases.

- **Documentation**  
  Usage instructions and more detailed guides will be added to the GitHub repository gradually.

**Note:** It's recommended to restart the device after installing this update so that all services and iptables rules initialize correctly.

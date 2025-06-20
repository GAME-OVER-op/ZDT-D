# Version 1.1.2  
**Stable Release**

## What's New
- Added backup and restore feature for settings to allow sharing between users.  
- Aggressive zapret config added.  
- Some web interface fixes.  
- Added ipset support (falls back to standard if unsupported).  
- Custom IP lists: place `.txt` files in a `ZDT-D` folder with entries like `0.0.0.0/0 ipv4` and `0:0:0::/0 ipv6`.  
- Web interface now launches faster if the service was stopped.  
- Console settings available when WebUI fails (`su zapret --help`; Russian only).  
- Updated zapret binaries to version 71.1.1.  
- Various core code improvements.  
- Fixed console `zapret stop` command that was not working correctly.  
- Fixed conditions for launching additional IP‑list files in the `ZDT-D` folder.  
- Added ability to specify package name in zapret 5 aggressive mode via web interface.  
- May resolve Discord connectivity issue.  
- Other code optimizations.

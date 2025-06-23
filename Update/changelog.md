# Version 1.1.4  
**Stable Release**

## What's New
- Major refactor in `mobile_iptables_beta` function: the rule-adding logic has been moved into a new helper `add_rule(chain, cmd)`.  
- In the ipset block, restored exactly four calls for both IPv4 and IPv6 (`PREROUTING` and `OUTPUT`).  
- During IP iteration mode, preserved retry logic and notifications from the secondary script, while `add_rule` now guarantees at most two rules per IP.

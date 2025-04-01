🚀 **Update: Fixed iptables Rules Installation Conditions – Stable Release**

**What's New:**

- Fixed the condition for installing iptables rules.
- Now, if the input file is empty, the UID iteration is skipped.
- If the output file is empty, the rule will not be installed.
- This resolves the issue where the existing logic would attempt to set traffic for the entire system when the output file was empty.
- The fix ensures that the installation function only applies rules when the proper conditions are met.


# INSTRUCTIONS

This document contains additional explanations, setup notes, and practical usage instructions for ZDT-D.

## How to use sing-box in the module

For easier profile creation, it is recommended to generate the configuration in **NekoBox for Android** instead of writing it manually.

NekoBox releases:  
https://github.com/MatsuriDayo/NekoBoxForAndroid/releases

### Step 1. Install and prepare NekoBox

1. Download and install **NekoBox for Android**.
2. Launch the app.
3. Open the side menu (three lines in the top-left corner).
4. Go to **Settings**.
5. Find **Service mode** and change it to **Proxy only mode** instead of **VPN mode**.

This is important because the module expects a proxy-style configuration workflow rather than a full Android VPN mode.

### Step 2. Import and test your server profile

1. Return to the main screen of NekoBox.
2. Obtain your server link or configuration, for example:
   - `vless://...`
   - `vmess://...`
   - `trojan://...`
   - or another format supported by NekoBox
3. Import or paste the configuration into NekoBox.
4. After the profile appears, press **Connect**.
5. Run a **ping test** or otherwise confirm that the profile is actually working.

> **Important:** the profile should be tested successfully before exporting it for use in ZDT-D.  
> If it does not work in NekoBox, it will most likely not work correctly in the module either.

### Step 3. Export the configuration from NekoBox

After confirming that the profile works:

1. Stop the connection.
2. Open the profile sharing/export options.
3. Select:  
   **Share config → Configuration → Export to clipboard**

### Step 4. Import the profile into ZDT-D

1. Open **ZDT-D**.
2. Go to **Programs → Sing-box**.
3. Press **Create profile**.
4. Enable the created profile.
5. Press the **edit** button (pencil icon).
6. Tap and hold the configuration field, then use **Paste** to insert the exported config.
7. Check the inserted text carefully.
8. Save the profile.

> **Warning:** on some systems, if you use the keyboard suggestion popup or quick paste helper instead of a normal long-press paste into the field, **only part of the configuration may be inserted**.  
> Always verify that the entire configuration was pasted correctly.

### Step 5. Assign apps and start the service

1. Select the applications that should use this **sing-box** profile.
2. Save the selection.
3. Start the **ZDT-D** service.

---

## Questions and answers

### What protocols can be used?
Any protocols supported by **NekoBox** can be used, as long as the exported configuration is valid for sing-box.

### What happens if multiple profiles from different servers and regions are used?
The internal balancing logic of **t2s** may distribute traffic between them depending on the current state and availability of the servers.

### If the server goes down, will the Internet stop working completely?
No. In this scenario, **t2s** can route traffic directly instead of sending it through the unavailable server.

---

## Notes

- Always verify that the profile works in **NekoBox** before exporting it.
- Always verify that the full configuration was pasted into **ZDT-D**.
- Behavior may vary depending on Android version, ROM, keyboard, and clipboard implementation.
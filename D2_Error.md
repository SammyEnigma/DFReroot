## D2 Error fix

Galaxy firmwares around Apr 2026 introduce Odin lockdown feature. On lock screen enabled devices, Odin mode can't be accessed anymore.
DFReroot v2.1.0 and later has a countermeasure for this lockdown by writing flags on every boot.
DFReroot app detect thos firmwares and modifies lockdown flags in "DMC Vault". Bootloader (abl on qualcomm chipset devices) reads those flags to decide whether to show D2 error or launch Odin mode when Odin mode was requested.
The vault has 3 boolean flags, $ \[lock, maintenance, at_command\] $ and abl shows D2 error if those values are $ \[1, 0, 0\] $.
DFReroot set at_command flags to 1 on every boot to prevent lockdown. DFReroot sets this flags after system service (mentioned later) reset those values with ~1 sec delay. Note: If you reboot the device in this ~1 second gap, the device rejects to enter Odin mode.

DFReroot app shows current status like "D2 Vault: lock=1 maint=0 at=1 - Odin allowed" on screen.


### System behavior
Those firmwares has system service named `com.android.server.DmcService` on their `system\_server`, and the service monitors lock or maintenance mode configuration to reflect those changes into "DMC Vault" via `VaultKeeperService`.
And also do following things:
1. Apply current lock status on `system\_server's` boot phase. (Save $ \[current lock state, current maintenance mode state, 0\] $ to the vault.)
2. Receive AT command (`AT+SUDDLMOD=..` or `AT+FUS?`) and apply at_command flag on vault. (This flag will be cleared on next boot)

### Where is the "DMC Vault" stored?
`vaultkeeper` (vk_a/vk_b) TA in TEE is responsible for managing those values (perhaps) securely. I havn't dug the implementation deeply yet.

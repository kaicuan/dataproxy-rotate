# DataProxy rotation requirements

This file records settled requirements from the conversation for `dataproxy-rotate`.

## Goal

- Allow a remote client to request a rotation of the phone's cellular IP.
- The client side is out of scope for this project.

## Rotation mechanism

- Toggle airplane mode with `cmd connectivity airplane-mode enable` and `cmd connectivity airplane-mode disable`.
- Keep airplane mode on for a configurable delay.
- Use a default delay of 1 second.
- Run the enable, delay, disable sequence in the rotation function by calling the helper operations.

## Public IP verification

- Record the public IP before a rotation and check it again after the rotation.
- If the IP has not changed, retry the rotation, up to 3 total attempts.
- Fail the rotation if the IP still has not changed after 3 attempts.
- Detect the public IP with a STUN binding request over a cellular-bound UDP socket, using the STUN server selected on the Rotation screen.
- Any IP check failure marks the rotation unverified. It does not abort the rotation and does not count as a failed rotation attempt.

## Privileged helper

- Build a small Shizuku-like bootstrap mechanism into DataProxy.
- Follow Shizuku's wireless debugging model.
- Use Android Wireless Debugging pairing to authorize DataProxy as an ADB client.
- Pair only once and store the ADB key.
- Discover the pairing port with mDNS `_adb-tls-pairing._tcp` and the connect port with `_adb-tls-connect._tcp`.
- Discover the pairing and connection ports with mDNS. If discovery fails, ask the user to retry with Wireless debugging enabled; do not ask for port numbers.
- Connect DataProxy to the phone's own adbd and launch a helper process as the shell user, UID 2000.
- Use Wireless ADB only to bootstrap the privileged helper.
- During normal operation, DataProxy communicates locally with the helper, which controls airplane mode.
- Use a loopback TCP socket on 127.0.0.1 with a random port and a session token for the local helper communication. SELinux blocks an app from connecting to a shell-created abstract Unix socket.
- Helper operations include enable airplane mode, disable airplane mode, apply Keep Wi-Fi On, and check helper status.
- Each operation owns availability and repair: it tries the call, repairs the helper if needed, then retries.
- Callers invoke the operation directly and wait for the result. They do not check, repair, or retry the helper.
- The layer does not watch or repair in the background.
- An existing library may provide the ADB pairing and client work.
- Rotation requires Android 11 or newer. The SOCKS5 proxy keeps Android 8+ support.

## Helper setup

- The first setup requires one manual trip to Wireless Debugging settings.
- Post a notification with a pairing code field so the user can enter the code without leaving Settings.
- After pairing, grant DataProxy `WRITE_SECURE_SETTINGS` through the shell helper.

## Rotation control

- Rotation requests are handled by `ProxyService`.
- Only one rotation may run at a time.
- A rotation request returns immediately after starting the rotation.
- A status request reports the current or most recent rotation. No operation ID is needed because only one rotation runs at a time.
- Share the SOCKS5 listener address and port for rotation control. Do not add a second port.
- Dispatch SOCKS5 and HTTP connections by the first byte.
- Use HTTP Basic auth with the existing SOCKS credentials when auth is enabled.
- Allow unauthenticated rotation control when SOCKS auth is disabled.

## UI

- Rotation appears as a single row in Settings that opens a Rotation screen.
- The Rotation screen contains, in order:
  - The enable toggle with its state.
  - Airplane mode delay.
  - Keep Wi-Fi On.
  - IP check server selection in a single column, with a test button beside its description.
- When rotation is off, keep timing and IP check visible but disabled, and explain that enabling rotation makes them editable.
- Explain that each rotation toggles airplane mode to reconnect mobile data, and how long the delay keeps airplane mode on.
- User-facing text never names the helper. The UI talks about IP rotation and its state.
- While rotation is on, show only `Checking...`, `Needs setup`, or `Ready`.
- If rotation is on but unavailable, clearly say requests will not work until setup is complete. Use one `Set up` action for every unavailable case; do not show a separate reconnect or failure state.
- `Set up` opens one guide without starting pairing. Notifications have a separate permission/settings action; request `POST_NOTIFICATIONS` on Android 13+ and link to notification settings when blocked.
- The guide's separate Developer options action starts pairing and opens Wireless debugging only when notifications are allowed. Closing the guide cancels a pending pairing attempt and restores the `Needs setup` action; an in-progress connection continues.
- Keep the pairing notification concise, report when mDNS finds the pairing service, accept the pairing code inline, and dismiss it as soon as the code is submitted. Re-show it only when pairing fails; helper startup retries must not request the code again.
- The guide tells the user to open Wireless debugging and tap "Pair device with pairing code".
- When rotation is off, show no state and no setup action.
- The Settings row subtitle is `Off` when rotation is off. When rotation is on, it is `On`, `On, checking`, or `On, needs setup`.
- IP check choices are `stun.nextcloud.com`, `stun.cloudflare.com`, `stun.l.google.com`, and Custom.
- Default to `stun.nextcloud.com`.
- Custom accepts a host and port.
- User-facing UI calls this "IP check", not STUN. Show provider names (Nextcloud, Cloudflare, Google, Custom) with the hostname as secondary text.
- Put the IP check `Test` button beside its description. It uses the cellular-bound socket, shows only a spinner while running, and reports success or failure in a Snackbar.
- The manual rotation test shows `Running...` on its button and reports the result in a Snackbar. Do not show a separate rotation status card.
- Explain that DataProxy uses the selected server to check the public IP after each rotation and retries up to three times if it has not changed.
- Copy: "If the IP has not changed, DataProxy retries up to 3 times. If the check fails, rotation still runs but cannot be verified."
- On failure, say rotation still runs but cannot be verified.

## Helper lifecycle and user control

- The helper does not survive a reboot.
- The user can enable or disable the rotation feature.
- Store the user's preference separately from helper status.
- If the helper dies, keep the preference on.
- Do not silently switch rotation off.
- SOCKS5 proxying continues while rotation is unavailable.

## Keep Wi-Fi On

- Keep Wi-Fi On is part of the rotation feature.
- Enable it by default.
- Rotation still works when Keep Wi-Fi On is disabled.
- Read the current state with `settings get global airplane_mode_radios`.
- A successful read whose list does not contain wifi means enabled.
- A failed read or an unset value such as null means unknown, not enabled.
- To change state, run `settings get` immediately before `settings put global airplane_mode_radios <radios>`.
- Preserve every radio entry except wifi. Remove wifi to keep Wi-Fi on, add wifi to let airplane mode disable it.
- Read the value back after writing to confirm the change.
- The setting change is global and persists. Turning Keep Wi-Fi On off adds wifi back to the list.

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
- Fall back to manual port entry if mDNS discovery fails.
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
  - IP check server selection with a test button.
- User-facing text never names the helper. The UI talks about IP rotation and its state.
- The setup flow explains that DataProxy needs to turn airplane mode on and off, and that Android only allows this through Wireless debugging.
- When rotation is off, show no state and no setup action.
- When rotation is on, the state is one of:
  - `Checking...`: checking or repairing. No action button.
  - `Needs setup`: "IP rotation needs a one-time setup." Show a `Set up` action.
  - `Needs reconnect`: "IP rotation needs to reconnect." Show a `Reconnect` action. If repair fails, stay in this state and show the reason inline. There is no separate failure state.
  - `Ready`: show a `Test` action that runs one manual rotation and shows the result.
- The Settings row subtitle is `Off` when rotation is off. When rotation is on, it is `On`, `On, needs setup`, or `On, needs reconnect`.
- IP check choices are `stun.nextcloud.com`, `stun.cloudflare.com`, `stun.l.google.com`, and Custom.
- Default to `stun.nextcloud.com`.
- Custom accepts a host and port.
- User-facing UI calls this "IP check", not STUN. Show provider names (Nextcloud, Cloudflare, Google, Custom) with the hostname as secondary text.
- The test button is `Check my IP` and uses the cellular-bound socket. It shows the detected public IP or the failure.
- Copy: "After each rotation, DataProxy asks this server for your public IP. If it didn't change, it retries up to 3 times."
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

# S09.02 service startup and VM-dependency UX (design; physical pending)

## The dependency is real

GBCode's guest components live in the Terminal VM. Google's Terminal preview documents VM persistence separate from the terminal window as **roadmap** — the VM is tied to the Terminal app's lifecycle today. The app must show this honestly rather than pretend the VM persists.

## States the app must surface

- `not_configured`: no pairing yet → onboarding.
- `vm_unavailable`: paired before, but the daemon is unreachable → "Start the Terminal app" guidance + reconnect action (never silently fall back to a local empty project).
- `degraded`: reachable but the session expired/identity changed → re-pair prompt with the new fingerprint.

## Service management (guest)

- If `systemctl --user` works: the bootstrap installs `gbcode-sessiond.service` (done in S09.01; the doctor reports `systemdUser`).
- Else: supervised foreground launch with clear stop instructions. A `tmux`/`nohup` process survives a terminal TAB closing but not VM destruction — the app must not claim otherwise.
- The APK never starts/stops the VM itself (no documented third-party API; ADB is a dev tool, not production).

## Emulator-lane evidence today

- Bootstrap service-file installation + fallback instructions tested on the workstation (`docs/evidence` S09.01 lane; systemd --user unavailable there, so the fallback path was exercised).
- The app shows `restored pairing` and reconnects the workbench across its own process death; VM-level lifecycle is the physical lane.

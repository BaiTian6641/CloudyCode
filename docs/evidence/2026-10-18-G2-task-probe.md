# G2 evidence — synthetic Android task behavior

**Date:** 2026-10-18. **Device:** Googlebook `C1L63708PZ`, user 10, SDK 37.
**Probe:** `dev.cloudy.x11` debug (`TaskProbeService` / `TaskProbeActivity`),
adb-triggered only. **Source:** `task` probe on top of `a0c5af8`, committed with
the probe. No token/cookie is recorded. This is a platform probe, not the X11
window host.

## What is established

| Claim | Result | Evidence | Limit |
|---|---|---|---|
| Two independent free-form tasks can be created while the app is visible. | **Yes.** Task IDs `1001064` (`two-a`) and `1001065` (`two-b`), both resumed/laid out with distinct titles. | Lifecycle log and `dumpsys activity activities`. | Does not prove background authority or every system surface. |
| Initial launch bounds are honored. | **For these tasks, yes.** A `(268,161)-(894,585)`, B `(865,484)-(1491,908)`, each `626x424`. | ActivityOptions vs `WindowMetrics` logs. | These include host/caption bounds, not measured content-only bounds. |
| Dynamic task title updates. | **Yes.** Both changed to `Cloudy probe A *` / `Cloudy probe B *` 3 s after creation. | Lifecycle log. | Caption, taskbar, Recents and Alt+Tab were not separately verified. |
| A foreground-service command creates a task after HOME + 16 s. | **Not demonstrated.** Both `startActivity` calls returned; no `onCreate`/resume for those IDs appeared in the captured interval. | Service log; launcher resumed. | No ActivityTaskManager/BAL denial was preserved, so the exact policy/allowance is unproven. |
| A later task was created while another app was expected to be foreground. | **New task `1001067` created** (`one-4`). | Log/dumpsys. | The existing probe tasks resumed before the request; this is not proof of fully obscured launch. Task bounds were adjusted from `320x240` to `357x357`. |
| Notification permission/user interaction was verified. | **No.** `pm grant ... || true` produced no captured result; notification runtime/app-op state was not dumped. The notification content intent only starts the service and does not create a window. | Source/manifest, no runtime dump. | Do not claim a notification fallback. |
| Force-stop cold activation works automatically. | **Not tested.** Force-stop was used as preparation; the UI was explicitly launched afterwards. | Command history. | Separate cold-start route required. |

Raw sanitized excerpts are in the source record for commit `b363fdd`; the
original `/tmp` screenshots are **not** durable evidence and are not relied on
as public artifacts. The HOME screenshot only shows the launcher with no probe
window.

## Relevant observed log (sanitized)

```
21:02:43.810 action=two trigger=visible startId=2
21:02:43.835 startActivity two-a bounds=Rect(268,161-894,585)
21:02:43.871 startActivity two-b bounds=Rect(865,484-1491,908)
21:02:43.901 create two-a task=1001064 bounds=268,161,894,585 626x424
21:02:43.934 create two-b task=1001065 bounds=865,484,1491,908 626x424
21:02:46.907 title-update two-a 'Cloudy probe A *'
21:02:46.934 title-update two-b 'Cloudy probe B *'
21:03:11.985 action=two trigger=home-16s startId=3
21:03:11.992 startActivity two-a-3 bounds=Rect(112,67-432,307)
21:03:11.994 startActivity two-b-3 bounds=Rect(363,203-626,424)
   (no create/resume for two-a-3/two-b-3 in that capture)
21:03:23.086 action=one trigger=settings startId=4
21:03:23.133 create one-4 task=1001067 bounds=112,76,469,433 357x357
```

## Required follow-up before C3.2/G2 acceptance

1. Capture the actual ActivityTaskManager/BAL outcome for the HOME+16 s request
   with a full log buffer (not a filtered tag), without clearing history first.
2. Repeat with Terminal-origin and Cloudy Code-origin synthetic events through
   the probe transport; adb service starts are not those sources.
3. Test obscured, Recents/minimized, no-visible-task-but-service-running, and
   force-stop as separate states; record any user-grantable permission or
   foreground condition.
4. Verify dynamic icon/caption/taskbar/Recents/Alt+Tab separately, and close /
   Back / task removal / Activity recreation / Surface replacement.
5. Only after a supported automatic route exists should the native window host
   claim automatic task creation. A notification action may be a labeled
   fallback, not full automation.

**Status:** G2 partially verified (visible task identity/bounds/title); background
launch remains unproven. C3.2 must not claim automatic window creation yet.

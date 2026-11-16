# Reels Guard v1.4 (Android)

Experimental Instagram Reels blocker using an Android AccessibilityService.

## What changed from v1.3

- Detect Reel-viewer signals conservatively; avoid treating the persistent Reels nav label as a Reel.
- Route to Home, with a **single Back fallback** per detected Reel session; 1.5-second action cooldown.
- Explore touch shield can intercept vertical swipes while forwarding taps and horizontal photo swipes.
- Detect comment sheets and pause the shield while comments are open.
- Coalesce accessibility events to avoid heavy repeated UI-tree scans.
- No network permission, analytics, or storage of Instagram content.

**Limitations:** Instagram's accessibility UI differs by version/language. Detection may miss Reels or occasionally redirect normal content. The experimental shield can interfere with taps or gestures, and the fallback when an overlay cannot appear only exits the viewer **after** a scroll. This app cannot modify Instagram or guarantee that every Reel is blocked. Accessible labels are currently oriented to English; additional languages require validation.

## Build APK on a phone using GitHub

1. Open the repo's **Actions** tab.
2. Open **Build Reels Guard v1.4**.
3. Tap **Run workflow**, choose branch **reels-guard-v1.4**, and run it.
4. Open the successful workflow run; download artifact **Reels-Guard-v1.4-debug**.
5. Extract the ZIP and install **reelsguard-debug.apk**.
6. Open Reels Guard and enable its service in Android Accessibility settings.

The workflow also runs automatically on pushes to this branch. Builds are **debug signed**. An APK signed with a different key from v1.3 cannot install over it; back up settings as needed and uninstall the previous version first. You must then re-enable the Accessibility Service.

## Local build

Requires JDK 17, Android SDK Platform 35 and Gradle 8.11.1.

    gradle :reelsguard:assembleDebug

Output: reelsguard/build/outputs/apk/debug/reelsguard-debug.apk

## Privacy and scope

The accessibility service is configured only for com.instagram.android. It reads visible accessibility labels locally to identify screens and, if enabled, uses a temporary accessibility overlay to intercept gestures. It requires no INTERNET permission and has no network dependency.

## Testing checklist

- [ ] Reels tab redirects once, not a repeated Back loop
- [ ] Instagram Home feed, DMs and Stories remain usable
- [ ] Open an Explore post: taps and horizontal photo swipes work
- [ ] Vertical Explore swipe is consumed while shield is enabled
- [ ] Comments button opens a sheet; vertical scrolling within comments works
- [ ] Closing comments restores the Explore shield
- [ ] Leaving Instagram removes the shield
- [ ] Turning off Explore shield restores ordinary swipe behavior

To protect normal Instagram interactions, turn off the experimental shield if it fails on your Instagram build.

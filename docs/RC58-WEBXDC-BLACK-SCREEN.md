# RC58 shared-app black screen — unresolved

Reported November 17, 2026 after RC58 publication. User reports several shared apps open to a black content area on both phones. The app name, Open apps button, three-dot menu and close button remain visible. Do not describe RC58 Webxdc rendering as device-verified or fixed.

## Source inspection

Inspected securejoin-invites WebxdcRuntime.kt, WebxdcNetworkGuard.kt, webxdc_wrapper.html, webxdc.js and the MainActivity session host. The wrapper invokes startupFinished(true) on the app iframe load event. Kotlin additionally requires that /index.html was served. Neither proves that app JavaScript initialized or rendered usable content. An app script exception can therefore leave a blank viewer after the loading indicator disappears. This is a confirmed diagnostic coverage gap, not a confirmed root cause of this report.

The native bridge log method discards its input, and the viewer has no onConsoleMessage handler. HTTP asset failures return an empty 403 response; the viewer handles onReceivedError but not onReceivedHttpError. Preserve sandbox network isolation while investigating; do not bypass guards as a speculative fix.

## Required next work

1. Reproduce using the actual Android WebView on an emulator/device with a simple known-good Webxdc and catalog apps. Test the initial launch, minimize/reopen and multiple shared instances on sender and receiver.
2. Capture main-frame and app-frame loading, asset response failures, JavaScript console exceptions and measured WebView bounds. Avoid recording chat contents, app payloads or full sensitive URLs.
3. Distinguish wrapper/guard failure, resource loading, JavaScript initialization and view visibility/lifecycle issues. Fix the demonstrated failure, then add a regression check that executes the viewer rather than merely validating an archive or helper function.
4. Run required Gradle tests/build and actual WebView rendering checks. Build the next APK with the existing saved RC58 signing key; never rotate it for this bug. See RECOVERY.md.

## Verification status

No new code fix or APK was produced for this report. Source inspection only: the executor/Android build and runtime-testing environment was unavailable during this session. The previous 171 passing automated tests did not establish successful rendering on either phone. RC58 remains the published build, with this reported defect unresolved.

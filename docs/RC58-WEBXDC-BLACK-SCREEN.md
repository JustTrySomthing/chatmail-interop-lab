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


## RC59 phone evidence, November 17, 2026

User copied this report from an affected app (app name not yet supplied):

```
Relay Lab 0.7.23-rc59 (95)
Android 36; Webxdc diagnostics
Frame loaded=true; startup error=false; renderer failed=false
View size=1080x1886; visible=0
JavaScript error; line 0
WebView 157.0.8103.50; guard=true
Missing or unreadable app asset
App entry served
HTTP 403; main=false
Webxdc API served
Frame load callback=true; entry served=true
```

The earlier verification paragraph describes RC58-era investigation, not the current build status. RC59 was subsequently published with 172 passing unit tests and verified signing/packaging. This report is actual phone evidence but does not establish a functioning Webxdc UI.

Source-based interpretation: visibility 0 means Android View.VISIBLE. Native WebView bounds are nonzero. The wrapper passed its isolation checks and served index.html and webxdc.js. Its frame load callback does not establish successful application initialization. At least one local archive asset lookup failed; this handler returns HTTP 403 even for a missing resource, so that code alone is not evidence of a remote server/access-permission problem. The unidentified asset could be an optional favicon or an essential script/style. The generic console message and line 0 do not distinguish those cases. Do not disable isolation or claim the missing asset is the root cause. Obtain the exact tested app name/package to inspect its archive and resource references; improve resource-specific diagnostic categories if still needed.

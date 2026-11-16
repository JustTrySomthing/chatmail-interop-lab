# rc30 call and app-log restoration

Base: published rc29 source (local commit 92663cd). Included in rc30 (66). This work does not establish successful two-phone calling.

Restored from released rc27 behavior:
- Start the 45-second outgoing ringing window only after local offer preparation.
- Cancel the incoming ringing timer on acceptance; start the 30-second connection window after answer preparation. Restore caller connection allowance to 30 seconds.
- Restore audio-device error callbacks, ICE counts/error codes, remote-description stages, audio focus diagnostics and Chatmail ICE retrieval errors.
- Preserve candidate-less answers with valid ICE credentials; reject unroutable offers with an explicit reason instead of an anonymous check failure.
- Restore Clear log, immediate screen refresh, and preservation of the historical-exit watermark so cleared crashes do not reappear.
- Log signal queued/received/delivered stages without logging SDP, addresses or TURN credentials.

Remaining evidence needed: receiving phone App log for the matching call attempt. Caller rc29 log reports one failed preparation and one prepared offer, with no observed answer or connected event. It does not identify why the receiver did not complete the call. Do not claim all calling is fixed or publish as verified two-phone calling.

Code and resource shrinking remain disabled. Keep the existing rc28/rc29 signing key. rc30 updates directly over rc28/rc29 using the same signing key.

## Subsequent evidence — 2026-11-14

After installing rc30, the owner reported that calling worked. The earlier request for receiving-phone failure evidence is historical, not a blocker for continuing. This is a user-confirmed successful call, not exhaustive physical-device validation.

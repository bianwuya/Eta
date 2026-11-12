# G4 independent Images protocol

ImageGenEndpoint resolves a root URL, /v1 or complete /images/generations route once. Unknown custom suffixes require the explicit endpointOverride field; valid advanced routes are preserved. URLs reject embedded credentials, query/fragment, malformed protocol and repeated standard paths. HTTPS is required except literal localhost/loopback HTTP for local fixtures. Existing complete endpoints remain compatible; new config protocol/override fields have safe defaults. Credentials still use the original device-bound AES-GCM storage and independent imagegen key slots; no language-provider config or key migration occurs.

ImageGenClient builds the normalized target and retains one POST, no redirects or transport retry, independent model/key, fixed subscription account when explicitly selected, unknown-outcome failures and official image viewer path. A narrow output callback permits deterministic disk-error injection; default remains ImageGenOutput.save. No visible page changes, fictional provider or paid request added.

Fresh source snapshot g4-fake-native: tree 461036b0438b3532bc1a3a3204369ff63c75c6e3, 8 suites / 22 tests / 0 failures / 0 skipped, exit0. See task runs/g4-fake-native. Fake HTTP server received root, v1 and complete forms at /v1/images/generations, verified fixed bearer/model and saved real PNG bytes. Covered 401/403/404/429/500/502/503, redirect, empty/malformed payload, decode/storage failure and cancellation before POST. Existing encrypted-storage/isolation/legacy-config tests retained.

One fixture compile error was corrected to this Robolectric version's getApplication() signature. PNG success needed native graphics (the default legacy bitmap fixture did not encode a decodable image); enabled native graphics for this test, without changing production decode or assertions. This is a test-host correction, not product fallback.

No credential material was read or changed. Source SHA and rollback refs are recorded in [CHECKPOINTS.json](../../../CHECKPOINTS.json). G5 preview approval is required before new endpoint/ordering/statistics business UI is implemented.

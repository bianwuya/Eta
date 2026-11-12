# URL and Images validation

| Input/state | Expected |
|---|---|
| https://relay.example with optional / | /v1/images/generations |
| https://relay.example/v1 with optional / | /v1/images/generations |
| Complete standard endpoint | Preserved, one suffix |
| Complete prefixed endpoint | Preserved |
| /v1/v1 or duplicated images/generations | Reject; no HTTP |
| Custom suffix in basic field | Reject with advanced override guidance |
| Explicit endpointOverride | Preserve valid full path |
| Userinfo/query/fragment/non-HTTPS remote | Reject; no credential leak |
| Loopback HTTP | Allowed for deterministic local server |
| No language provider | Independent profile/key/model POST succeeds |
| 401/403/404/429 | One request, explicit failure |
| 5xx / disconnect / decode / disk failure | Unknown outcome, no retry |
| 302 | No redirect, no second paid POST |
| Empty data / invalid PNG / cancelled controller | Failure or cancellation, no partial success |

Executed ImageGenEndpointTest, ImageGenFakeServerTest and existing ImageGen* tests. Native fixture PNG is two pixels, no production account/model used. Actual log and XML: task runs/g4-fake-native. Real remote capability, service quota, Android device and final UI remain untested by design.

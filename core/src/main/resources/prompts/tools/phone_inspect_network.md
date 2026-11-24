Inspect recent HTTP network requests and responses captured from the current target app on the virtual display.

Returns recent network events with their requestId, URL, HTTP method, status code, and latency. Use this to discover API endpoints, verify if an action triggered a network call, or find the requestId for phone_get_network_response.

The history is a rolling window, so the result also reports retention (kept, capacity, trimmed, oldestKeptAt). Read it before concluding anything:
- capturing false, or captureError / warnings present: this app is not being decrypted, so querying again gives the same answer. Read the screen instead.
- count 0 with trimmed above 0: the request happened earlier and was evicted. Repeat the app action, then inspect once.
- count 0 with trimmed 0: nothing has been captured in this window yet. Make a request really happen (the action, or phone_wait_network), then inspect once.

Two empty looks mean this route is unavailable for this task: fall back to reading the screen.

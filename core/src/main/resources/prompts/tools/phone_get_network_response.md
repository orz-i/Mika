Extract the captured HTTP response body (typically JSON) for a specific network request by requestId or by matching a URL pattern.

Use this when you need exact structured backend data (e.g., order ID, tracking number, status, detailed list items) directly from the API response instead of reading or parsing imperfect visual text from the screen.

not_found means the body is not in the current capture window (wrong requestId, or it was evicted — see retention), not that the app has no such data: repeat the action to capture the request again or read the screen, instead of retrying the same requestId.

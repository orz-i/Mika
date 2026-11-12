Wait for a network response from the current app matching a URL pattern (regular expression or substring), such as order creation, data refresh, or search API.

Use this after an action that starts loading or triggers a network request instead of repeatedly observing at a fixed interval. This wakes immediately when the HTTP response arrives, saving turn steps and avoiding premature screen observations while data is still loading.

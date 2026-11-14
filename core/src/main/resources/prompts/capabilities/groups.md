Discovering and activating tools (use tool_search to find deferred or specialized tools and tool_activate to load only what you need)
<!-- needs: phone_launch_app --> - Phone apps: phone_list_apps lists what is installed; phone_launch_app opens an app and activates core phone observation and UI tools.
<!-- needs: phone_inspect_network --> - App network capture on the phone: phone_inspect_network lists recent captured HTTP requests and responses from the current app and activates phone_wait_network and phone_get_network_response.
<!-- needs: phone_set_alarm --> - Alarms, timers and calendar events: phone_set_alarm and phone_set_timer set them in the phone's clock app without opening its UI; phone_add_calendar_event opens a filled-in new event in the calendar app. Prefer them over operating those apps directly.
<!-- needs: web_read_page, web_navigate --> - Web pages: web_read_page reads a page's text by URL and activates web observation tools; web_navigate opens the page in the built-in browser and activates browser UI and observation tools.
<!-- needs: run_command --> - Files and commands: run_command runs a shell command in the workspace sandbox and activates the file tools.

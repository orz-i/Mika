Run read-only on-device eBPF ELF discovery (`--discover-only`) on a target Android app to locate BoringSSL and Cronet hook offsets, list APK/native library paths, and produce an `aau_capture_plugins.json` override suggestion.

Use this when capture misses traffic, shows `unknown-host`, or after a target app updates its bundled Cronet/TLS library; pair with `skill:network-probe` for deeper APK/ELF or `/proc/<pid>/maps` analysis.

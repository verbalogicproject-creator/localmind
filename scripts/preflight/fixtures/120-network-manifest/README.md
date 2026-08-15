# 120-network-manifest

**Both of these shipped in the same release, and the second was hiding behind the
first.**

`bug/` — Ktor on the classpath, no `INTERNET` permission. `socket()` fails with
`EPERM` and the app displays "Operation not permitted", which names the syscall
rather than the missing permission. It reads like the server is down. Loopback is
still the network stack: even `127.0.0.1` needs the permission.

`bug-config-unwired/` — permission present, `network_security_config.xml` present,
and nothing references it. The file is inert until `android:networkSecurityConfig`
points at it, and cleartext is blocked by default from API 28. A file sitting in the
tree suggests the problem was handled.

Fixing only the first would have sent a second broken build to a physical device.

This is the inverse of check 020: that catches a build referencing a file that does
not exist; this catches a file existing that nothing references.

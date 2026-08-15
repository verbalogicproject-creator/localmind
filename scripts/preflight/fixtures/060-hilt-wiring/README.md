# 060-hilt-wiring

**Incident:** `@AndroidEntryPoint` with no `@HiltAndroidApp` Application. Throws at
launch with "Hilt Activity must be attached to an @HiltAndroidApp Application".
Compiles perfectly. **Nine consecutive green builds shipped it.**

Three fixtures, because there are three distinct ways to get this wrong:

- `bug/` — the annotation is simply absent.
- `bug-comment-only/` — the annotation appears only inside a comment. **This is this
  corpus's SECOND near-miss failure:** a naive presence-grep matched the comment and
  reported success while the annotation was genuinely missing. Hence `^\s*@`.
- `bug-wrong-class/` — the annotation is real, but sits on a class the manifest never
  instantiates. Identical crash, and it looks correct in both files individually.

# 030-manifest-resources

**Incident 1:** `@mipmap/ic_launcher` referenced after an icon purge. AAPT aborts
resource linking, so the error names the manifest line rather than the missing file.

**Incident 2 — this shipped:** a scaffold templated the manifest to
`@style/Theme.Localmind` but left `themes.xml` declaring `Theme.Conformance`. The
first app the pipeline ever generated failed on it. `bug-theme-mismatch/` is that
exact case.

It slipped through because **this check had been dropped during extraction** — the
corpus went from 9 checks to 6 and nobody counted. That is why `bug-theme-mismatch`
exists rather than just `bug`.

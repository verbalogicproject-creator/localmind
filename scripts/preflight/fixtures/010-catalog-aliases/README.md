# 010-catalog-aliases

**Incident:** a build file referenced a `libs.*` accessor with no matching entry in
`gradle/libs.versions.toml`. Gradle fails at configuration time, so nothing compiles
and the error names the accessor rather than the missing catalog entry.

`bug/` uses `libs.totally.absent.artifact`, which the catalog does not declare.

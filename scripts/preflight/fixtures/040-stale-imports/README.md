# 040-stale-imports

**Incident:** a `GeminiApiClient` import left behind when the class was deleted. It
compiles nowhere and fails the whole build.

Scans `test/` and `androidTest/` too, because `./gradlew build` compiles unit tests
and a stale import there fails identically. That cost a round trip once: an
integration test referencing a deleted symbol blocked every build while nobody was
looking at test sources.

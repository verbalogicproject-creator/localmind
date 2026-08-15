# 020-build-referenced-files

**Incident 1:** `proguardFiles` named a `proguard-rules.pro` that was never created.
The build failed and cost a full CI round trip.

**Incident 2 (this check's own near-miss):** the pattern matched only the lowercase
`proguardFiles`, so `testProguardFiles("proguard-test-rules.pro")` sailed straight
past and the check reported "every build-referenced file is present" with that file
deleted. `bug-test-proguard/` is that exact evasion, kept so it cannot recur.

Note `bug/` spans multiple lines deliberately: a line-oriented grep cannot match it,
which was this corpus's FIRST near-miss failure.

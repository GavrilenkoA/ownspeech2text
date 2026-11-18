# Patches on top of llama.cpp

None at `b12520` (`ff6b8d17`). `build_android.sh` applies every `*.patch` here with `git apply`
(idempotent: an already-applied patch is skipped, a non-applying one fails the build).

Policy: a patch goes here only with a one-line reason in its header and a link to the upstream PR/issue.
Re-check on every bump; delete patches that upstream merged.

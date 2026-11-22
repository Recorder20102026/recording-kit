# Reservation-service locking investigation: project + recording kit

| Path | What it is |
|---|---|
| `input 1/` | The brownfield reservation service (baseline, bugs included). Open this folder in IntelliJ IDEA. |
| `input 1.zip` | Pristine copy of `input 1/`. Re-extract to reset before a re-take. |
| `recording-kit/TRANSCRIPT.md` | Step-by-step recording script: **DO** / **SAY** / **EXPECT** for all four phases. |
| `recording-kit/README.md` | Pre-flight checklist, list of changed files, expected results. |
| `recording-kit/solution/`, `recording-kit/solution.patch` | Final versions of every file the fix touches, and the whole fix as one patch. |
| `recording-kit/expected-output/` | Real outputs from a dry run, for comparison. |

Quick start (JDK 21+, no Maven or Gradle needed):

```bash
cd "input 1"
./scripts/test.sh            # baseline: 17 passed, 10 failed
./scripts/evaluate-designs.sh
patch -p1 < ../recording-kit/solution.patch
./scripts/test.sh            # after fix: 27 passed, 0 failed
```

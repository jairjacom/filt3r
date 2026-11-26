# Coding standards

Read during review (skill://code-review), not while writing. Judgement calls only; mechanical rules belong in CI (`release-check.yml`).

- **Hostile input:** anything from the file picker, share sheet, clipboard, a downloaded list, or text another app or the network controls (app labels, host names) is untrusted. Check it has a size cap, a type or content check, and a clear failure message; strip control and bidi characters and cap the length before showing or exporting it.
- **Partial failure:** when a download or import fails, the app keeps working with what it already has (own nodes, own lists) and says what failed.
- **Same shape as the neighbours:** new UI and strings follow the closest existing screen (`NodesPanel`, `strings_*.xml`); one helper for one job (see `LinkImport`).
- **Texts in resources:** user-visible text lives in `strings_*.xml`, not in Java string literals.
- **Beginner-readable:** plain Java, short methods, no clever generics.

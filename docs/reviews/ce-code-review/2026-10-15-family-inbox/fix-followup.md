# Review finding follow-up

Initial receipt: `review.json`, status `complete`, run `20261015-family-inbox-01`. This note records later fixes; the initial snapshot does not claim to review the new helper or lifecycle script.

- Applied #2 in one coupled frontend batch: added `CollaborationSync` to retain only pending POST promises across page instances. Inbox, detail, family events and unread badge wait before GET and recheck visibility/generation. Page hiding still clears authorized content. Actual-method probes reproduced four failures before the fix; final seven cases pass.
- Applied #3 in a separate backend test batch: `closingEventBeforeFamilyPublicationCancelsPendingAlert` acknowledges and closes through MVC while the family grant remains valid, then verifies publication cancels the pending message without a send time or event-state change. Its list and unread count remain empty.
- Rejected #1 was already covered by the actual-method detail GET-success/read-403 case. No justified finding remains unresolved.

Both fix agents returned terminal results without staging or committing. The orchestrator inspected the patches, ran all 34 Maven tests with real MySQL integration enabled (zero failures/errors/skips), ran all seven lifecycle probes, and rebuilt the final HAP successfully.

An independent focused pass by the backend-test author reviewed the frontend follow-up only: original POST resolution/rejection and cleanup, shared waiting across instances, all four visibility/generation checks, and hidden-content clearing. It found no new issue. It ran the seven probes; an in-memory mutation removing the waits failed six cases, and an injected permanent wait exited with code 1 through the five-second watchdog. `node --check` and whitespace checks passed. No second full review was invoked.

Lint configuration was inspected in both projects. The frontend declares `code-linter.json5` rules but no standalone lint command; the backend has no configured Checkstyle/PMD/SpotBugs runner. Static inspection and compilation do not establish a complete lint pass. Initial review concurrency and rollback coverage limits remain documented in `review.json`; no failing interleaving was established.

Final source/document whitespace checks passed. Raw `*.diff` review snapshots retain unified-diff blank context markers (a single space); these snapshots and the raw file-list EOF separators were excluded from the final whitespace check rather than rewritten as code.

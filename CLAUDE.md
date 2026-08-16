# CLAUDE.md

Guidance for any Claude Code session or subagent working in this repo. This
applies to the orchestrating session and every dispatched agent alike.

## What this project is

A hobby project, not a professional or gig-grade deliverable. Full context
in issue #1 (BRD) and the story issues it links to. Calibrate effort
accordingly:

- **Keep:** correctness that prevents corrupting the user's own pedal state
  during ordinary use (see the "fail fast and loud" philosophy below). That's
  cheap to build and expensive to regret regardless of project stakes.
- **Don't over-build:** hard latency targets, gig/soak-test rigor, or
  elaborate automatic-recovery safety nets aimed specifically at
  live-performance stakes. If a story reads as justified by "this has to
  survive a gig," treat that framing as historical and check with the
  product owner (or the issue's latest comments) before gold-plating it.

## Fail fast and loud

The house philosophy, already the pattern throughout `:protocol`
(`TonexError`, `TonexResult`): surface a typed error rather than guessing,
retrying silently, or reporting success on an operation that didn't fully
succeed. Reject-and-explain beats patch-and-hope. Keep following this
pattern in new code; it's the actual safety net for a project of this size,
not exhaustive defensive engineering.

## Session-limit resilience

Assume any agent invocation — orchestrator or subagent — can be terminated
by a usage limit at any moment, with no warning, including mid-task after
the real work is done but before it's saved. This has already happened
twice in this project (S7 and S8 fix rounds both completed their changes
and were killed during the final verification-and-commit step). Work
accordingly:

- **Commit early and often, not once at the end.** After each fix, or each
  small related group of fixes, commit — don't batch a long list of changes
  into one commit that only happens after everything is done and verified.
  A commit with a caught bug in it is cheap to fix next session;
  uncommitted work is gone if the container is reclaimed.
- **Push immediately after every commit.** A local commit in an ephemeral
  container is not durable. Only what's pushed survives.
- **Treat "verify, then commit" as backwards.** Prefer "commit the working
  change, then run full verification as its own separate, resumable step."
  If verification fails, that's a fast follow-up commit, not lost work.
- **Prefer several small, narrowly-scoped dispatches over one large one.**
  Reduces how much is at risk if any single dispatch gets cut off.
- **Don't trust a terminated agent's last message at face value — or a
  prior commit's claims.** If you're picking up a branch where the last
  commit message says "WIP," "tests not verified," or similar, re-run the
  verification yourself before continuing or reporting status upward. Status
  claims from a session that died mid-task may be accurate or stale; you
  can't tell which without checking. (This is not hypothetical: on this
  project, re-running the suite after two agents died turned out to
  contradict what one of them believed about its own test failures.)

## Branch discipline

- Every task or agent works on its own dedicated branch. Never commit to
  `main`. Never commit to another agent's or session's branch, including
  the orchestrating session's own branch.
- Name branches for the story they implement (e.g. `s8-state-blob-patching`),
  not just an opaque worktree ID, when the work corresponds to a real
  project story — it's how the next session finds unfinished work.
- No pull request gets opened or merged without being asked. Verified,
  pushed branches are the deliverable; merging is a separate, explicit step.

## Repo structure

- `:protocol` — pure Kotlin, JVM-only, Maven-Central-only. No Android
  dependency, no `google()` repo. Must keep building and testing with no
  Android SDK present; this is an explicit acceptance criterion (see #15),
  not an aspiration.
- `:app` — Android/Compose module. `google()` is scoped to `:app`'s own
  repositories only, never centralized in a way that leaks into `:protocol`.
- `docs/design/` — mockups and design tokens (D1/D2 stories). `S16-S19`
  (the actual screens) are gated on D2 sign-off (#6) — check whether that
  gate is closed before building real UI against these.
- `CREDITS.md` / `CONTRIBUTING.md` — attribution and source-header
  conventions for code ported from the upstream reference projects. Follow
  them for any ported logic.

## Environment notes that go stale — verify, don't assume

- Network egress policy is environment-specific and has changed mid-project
  before: issue #15 was once DEFERRED because `dl.google.com` returned 403
  in the cloud session, blocking all AndroidX/AGP resolution. That block was
  later lifted. If a story's issue text cites an environment limitation as a
  blocker, verify it's still true (a quick `curl` against the cited host)
  before deferring further work on that basis.

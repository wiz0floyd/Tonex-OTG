# CLAUDE.md

Guidance for any Claude Code session or subagent working in this repo. This
applies to the orchestrating session and every dispatched agent alike.

## Requirements

- Find/Install android-sdk at the start of any session.
- ALWAYS make sure main is in sync with origin before branching

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
- **Auto-schedule a check-in nudge whenever background work is in flight.**
  Any time this session dispatches agents that keep working without the
  user watching, or is itself waiting out a usage-limit reset, schedule a
  self wake-up by default — 5 hours out — using whatever scheduling tool is
  available (e.g. `send_later` / `ScheduleWakeup`), without waiting to be
  asked for it. Re-arm it at each check-in until the outstanding work is
  actually merged or the user explicitly says to stop. Don't assume you, or
  the user, will be there to notice when something needs attention;
  schedule the reminder as part of dispatching the work, not as an
  afterthought once someone asks for it.

## On sub agents 

Give sub agents a descriptive name when setting them up. Each sub agent must only write to its own work tree. read only agents may enter another agents working directory when collaborating or reviewing. e.g., when a sonnet or haiku agent asks an opus architecture agent to validate a decision, the opus agent should enter their directory to see the lines of code in question.

Match model cost to task risk and reasoning load, not uniformly:

- **Haiku** — mechanical, low-judgment work: posting/closing GitHub issues,
  filing routine comments, text edits with an exact spec to follow. Fast
  and cheap; don't spend a bigger model on rote GitHub hygiene.
- **Sonnet** — the default workhorse: implementing stories, writing tests,
  applying fixes from a review, general research and exploration.
- **Opus** — adversarial code review, and only that. Reserve it for stories
  where a wrong answer has real cost: anything that writes to the pedal,
  anything touching state that could corrupt a user's only hardware device,
  or adjudicating a disputed finding a Sonnet pass couldn't resolve on its
  own. Opus reviews on this project have twice found a genuine blocker that
  a Sonnet implementation pass reported as "criteria met, tests green" —
  that gap is what justifies the cost, so don't skip the review to save it.

## Review rigor, scaled to blast radius

Not every change needs the same scrutiny. Scale it to what's at stake:

- **High-stakes code** (anything that writes to the pedal, parses its
  responses, or touches state that could be echoed back corrupted) gets an
  adversarial Opus review before merge — not just a green test run. Brief
  the reviewer to assume the implementation is wrong until proven otherwise,
  to verify claims independently rather than trusting the diff's own tests,
  and to hand-check protocol literals against the actual upstream source
  rather than trusting a citation in the code or issue text. A prior
  session's citation of an upstream detail (an offset table's supposed
  version history) turned out to be fabricated; the reviewer only caught it
  by fetching the real upstream repo and checking.
- **When confirming a wire-format detail against upstream, check the sibling
  function for the opposite direction too, in the same pass — don't assume
  symmetry.** Upstream typically has one function that builds outbound
  frames and a separate one that parses inbound frames; they are not
  guaranteed to share a shape. S5 confirmed a 4-field message header against
  five outbound literals in `usb_tonex_one.c` and generalized that shape
  onto `decode()` as well, without ever opening `usb_tonex_one_parse` — the
  actual inbound parser sitting in that same upstream file — to check
  whether it agreed. It didn't (inbound is 3 fields, not 4). The bug was
  invisible through code review and a fully green test suite because the
  test fixtures simulated pedal responses using the same wrong 4-field
  model `decode()` expected — another instance of the S7 self-consistent-
  round-trip trap. It only surfaced against real hardware (#25), cost a
  probe round and a hand-decoded-hex hypothesis pass to re-diagnose, and
  the eventual fix (`48d59da`) was confirmed by simply reading the sibling
  parse function that had been sitting there the whole time. Read both
  directions' upstream functions before writing a codec that assumes they
  match.
- **If a fix round changes architecture** (not just patches a specific bug),
  re-review before merging — a green test suite after the fix proves the
  fix compiles and passes its own tests, not that it actually closes the
  gap the original review found. Don't treat "tests pass now" as
  equivalent to "reviewed."
- **Before filing something as "needs hardware verification," check whether
  upstream source or data already captured this session already resolves
  it.** A "needs real hardware" label is easy to reach for and easy to
  leave unquestioned by every session after the one that wrote it — verify
  it's actually true before adding more hardware-dependent work to the
  pile. Two S6 parameter-table discrepancies (`CABINET_TYPE`'s ordering,
  `VIR_MIC_2_X`'s max) had both sat marked "verify against real hardware
  (S20)" for the whole project, when reading `Builty/TonexOneController`'s
  actual source settled both outright — one from the enum definition and
  its real, tested usage outweighing a stray wrong comment; the other from
  upstream's own table declaring the value deliberately, with nothing
  suggesting a typo. Separately, S8's four state-blob offsets got marked
  "structurally compatible, not byte-level confirmed" after a hardware
  probe run — but the exact `GetState` response needed to confirm them
  byte-by-byte was already sitting in that same run's log; hand-decoding it
  (HDLC unstuff, CRC-verify, parse the header, index the four offsets)
  closed it without any new hardware pass or new write-path code. Check
  upstream and re-read what you already have before scheduling a hardware
  session, an editor cross-check, or new code to produce evidence you can
  get right now.
- **Findings that can't be resolved without hardware** (this project has no
  physical pedal in the cloud environment) should not be silently pinned as
  correct or silently dropped. Un-pin the code (a skipped/pending test with
  a clear reason), and file the exact resolving observation as a GitHub
  issue comment — usually on the hardware-probe story (#25 / S20) — so the
  next session with hardware access knows precisely what to check.
- **Byte-exact / literal-exact acceptance criteria are gold.** Writing
  tests against real captured or upstream literals (not just internal
  round-trip self-consistency) is what caught a real wire-format bug in S7
  that 100%-passing self-consistent tests had been hiding. Prefer literal
  assertions over "encode then decode and compare" whenever a real
  reference literal exists.
- **Review findings are a durable artifact, not chat output.** File them on
  the relevant GitHub issue (new issue, or a comment on an existing one) so
  they survive past the session that found them, whether or not they're
  fixed immediately.

## Standing merge authority

The product owner (@wiz0floyd) has granted standing permission to act
autonomously on pull requests, merges, and codebase decisions for this
project — no need to ask before merging reviewed, green, tested work.
Scope of that grant, and its one deliberate exception:

- **In scope:** opening PRs, merging PRs, routine engineering decisions
  (architecture, dependency choices, refactors, error-handling design) that
  a competent engineer would make without needing a stakeholder's input.
- **Still escalate:** non-obvious decisions with a real product-facing
  tradeoff — something a reasonable engineer could resolve two different
  ways and where the choice actually matters to the person using the app.
  When in doubt, escalate; the cost of asking is low.
- **The one standing exception, by explicit design:** D2's mockup sign-off
  (#6) and any future design-review gate that requires looking at the UI on
  the actual target device. Those are subjective visual/UX judgment calls
  that genuinely require the product owner's own eyes on their own phone —
  autonomy over "the codebase" does not extend to substituting for that.

## Branch discipline

- Every task or agent works on its own dedicated branch. Never commit to
  `main`. Never commit to another agent's or session's branch, including
  the orchestrating session's own branch.
- Name branches for the story they implement (e.g. `s8-state-blob-patching`),
  not just an opaque worktree ID, when the work corresponds to a real
  project story — it's how the next session finds unfinished work.
- Opening a PR is a routine step, not something to ask permission for — see
  "PR workflow" below. Merging follows Standing merge authority above
  (autonomous once reviewed, green, and tested; escalate only per that
  section's exceptions).

## PR workflow

The pattern of pushing a branch and writing status/review as issue comments
(used through S20/S21) skips the two things PRs are actually for:
line-anchored review comments, and a single mergeable unit with its own
diff and check state. Follow this instead, matching how a non-agentic team
would run it:

1. Implementer finishes the change on their own branch and runs the
   project's own verification (build, tests, lint) themselves first.
   **Lint specifically, not just tests:** `./gradlew :app:lintDebug` (in
   addition to `:app:testDebugUnitTest`/`:protocol:test`) — this is not a
   hypothetical omission. `:app:lintDebug` was red on `main` for the entire
   span of S11-S13 (`ContextCompat.registerReceiver`'s flags argument,
   `[WrongConstant]`) because every implementer across all three stories,
   and the Opus reviewer across four separate review passes, ran the test
   suite but never lint. It was the exact tool that would have caught the
   real crash bug (unconditional `IllegalArgumentException` on every API
   level) that S13 instead found the hard way by decompiling an AAR
   mid-story. Green tests are not a substitute for green lint; run both.
2. Once verification is green, implementer opens the PR against `main` —
   this is routine, not something to ask permission for. Put the
   what/how/verification writeup in the PR description, not a fresh issue
   comment.
3. Reviewer (Opus, for anything in scope of "Review rigor" above) reviews
   the PR's diff and posts findings as PR review comments anchored to the
   actual lines, not as a wall of prose on the issue.
4. Fixes for review findings land as new commits pushed to the same PR
   branch. Re-run verification before pushing.
5. The originating issue stays for scope, acceptance criteria, and
   product-owner decisions (e.g. a recalibration comment); the PR carries
   implementation and review, and closes the issue on merge (`Fixes #N` /
   `Closes #N` in the PR description).
6. Merge once review comments are resolved and checks are green, per
   Standing merge authority above — no separate ask needed for reviewed,
   tested work.

## Agent and session isolation

Every concurrent agent or session must work in its own dedicated git
worktree (or an equivalent fully separate clone) before touching any
file — never let two agents share a single working directory, even
briefly. This is not optional tidiness: it caused a real mess in this
project. Five dev agents were once dispatched without worktree isolation,
shared one directory, and ended up checking out each other's branches
mid-task; one agent's in-progress commit landed on a completely unrelated
branch and was only caught because it hadn't been pushed yet. Untangling
it cost an entire orchestration session that should have gone to actual
story work.

- When dispatching an agent that will write to the repo (orchestrator or
  subagent), either use the harness's own worktree-isolation option if one
  is available, or manually `git worktree add <dedicated-path> <branch>`
  and tell the agent its exclusive path before it does anything else.
  Never rely on an agent to "just checkout its own branch" in a directory
  another agent might also be touching.
- A shared read-only checkout (research, grepping, reading files) is fine.
  The rule is about concurrent *writes* — anything that runs `git
  checkout`, `git commit`, or edits tracked files needs its own worktree.
- If a collision is ever discovered anyway (stray files, a commit on the
  wrong branch), don't guess: check `git log <branch>..<other-branch>` and
  `git show` on the suspect commit before deciding what to keep, move, or
  undo, and prefer `git reset --soft` over `--hard` so nothing is lost
  while sorting it out.

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

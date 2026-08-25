# Spike: #95 — Equipboard signal-chain lookup + edge LLM Tonex suggestions

Status: **spike, not scheduled**. Research only, no code changes.

## What #95 asks for

> Add the ability to look up signal chain on equip board and use a
> lightweight edge llm model to suggest Tonex models that match.

Two coupled sub-problems:

1. Pull an artist/rig "signal chain" (amp + pedals) from Equipboard.
2. Feed that gear list to a small on-device model that suggests which
   Tonex Tone Models (captures) approximate that chain.

## Finding 1: Equipboard has no public API

- `equipboard.com/brands/api` returns HTTP 403 to an unauthenticated
  fetch — no open developer/API sign-up flow is reachable.
- Public write-ups (api-evangelist, apis.io) describe Equipboard as a
  community gear-discovery site, not confirm a public REST/GraphQL API.
  Their Feb 2026 platform update (album/track-level gear attribution) is
  framed as feeding Equipboard's *own* AI recommendation features, not a
  public data feed.
- No rate limits, auth scheme, or ToS for third-party API consumption are
  published anywhere found.
- **Consequence:** getting Equipboard data means either (a) contacting
  Equipboard for a partnership/enterprise data agreement, or (b) scraping
  their site, which is a ToS/legal risk this hobby project shouldn't take
  on without an explicit access grant.

## Finding 2: Tonex Tone Models have no public catalog API either

- Tone Models live in IK Multimedia's **ToneNET** (tone.net) — 17,000+
  amp/pedal/rig models across official IK content, Tone Partner
  collections, and community uploads.
- Search/browse is exposed through the TONEX app UI and the ToneNET web
  UI ("Smart Search"), not through any documented public API.
- **Consequence:** even with a gear name in hand ("Fender Twin Reverb"),
  there's no programmatic way to query ToneNET for matching Tone Models —
  matching would have to happen against a locally maintained/scraped
  subset, which nobody currently owns or maintains for this project.

## Finding 3: the edge-LLM half is the easy part, but it's not the bottleneck

- Google's on-device LLM stack has moved on since MediaPipe LLM Inference
  API (now maintenance-only) to **LiteRT-LM** for Android/Kotlin — small
  models like Gemma 3n E2B/E4B run acceptably on mid-range hardware
  (reports of fine performance on a Snapdragon 778).
- This part is buildable today: bundle or download a small LLM, prompt it
  with a gear list, get back suggested amp/pedal characteristics.
- But an LLM here only adds value as a *fuzzy matcher* over real gear and
  real Tone Model data. Without Finding 1 and 2's data sources, it has
  nothing authoritative to match against and would just hallucinate
  plausible-sounding but unverifiable Tone Model names — a bad fit for
  this project's "fail fast and loud" philosophy (no silent guessing).

## Recommendation

Don't schedule implementation yet. The blocker isn't the on-device model,
it's that both data sources (Equipboard, ToneNET) are closed platforms
with no confirmed public API. Two ways this could become buildable:

- **Manual/curated path (no external API needed):** ship a small,
  hand-maintained JSON mapping of common gear names → Tonex Tone Model
  IDs/names for a limited "greatest hits" set (classic amps/pedals),
  skip live Equipboard lookup entirely, and let the user type or pick
  their gear from that curated list. Feasible without any external
  dependency; scope and ongoing maintenance cost fall on the user/project
  owner.
- **Partnership path:** reach out to Equipboard and/or IK Multimedia
  directly about data access. Only worth pursuing if this feature is a
  priority — out of scope for a hobby-project spike to decide.

Given this project's calibration guidance (hobby project, don't
over-build), the curated-list path is the only one worth prototyping
without new information; the full "look up any artist's rig live" version
described in #95 is blocked on external access this project doesn't have.

## Sources

- https://equipboard.com/brands/api (403, no public API surfaced)
- https://github.com/api-evangelist/equipboard
- https://apis.io/providers/equipboard/
- https://www.globenewswire.com/news-release/2026/02/28/3246868/0/en/Equipboard-Introduces-Album-and-Track-Level-Gear-Attribution-Powering-a-New-Era-of-Music-Gear-Discovery.html
- https://tone.net/
- https://www.ikmultimedia.com/products/tonextonenet/
- https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android

# PingMe: builder rules

You are building PingMe, an open-source Android unified messenger. Read, in this
order, before doing anything: `docs/DESIGN.md`, `docs/UI_DESIGN.md`,
`docs/BUILD_PLAN.md`, then `docs/BUILD_LOG.md` if it exists (it tells you where the
last session stopped).

Rules:

1. Follow `docs/BUILD_PLAN.md` phase by phase, step by step, in order. Do not skip
   ahead, and do not start a phase until the previous phase's acceptance list passes.
2. The design documents are the specification. Build what they say. If something
   cannot be built as written, write the reason and the nearest alternative in
   `docs/BUILD_LOG.md` and ask the owner before narrowing. Never quietly stub or
   simplify a feature.
3. Every commit passes `./gradlew check`. Never disable, skip, or delete a test to get
   green. Never commit a broken build.
4. One plan step per commit or small group of commits, message prefixed with the step
   id (`P2.4: ...`). Push after every step so CI produces an APK.
5. Keep `docs/BUILD_LOG.md` current after every step: done, deviations from the plan,
   library version differences, what is next. It is the hand-off between sessions.
6. At a gate (marked **Gate Gn** in the plan), stop, push, and write the owner's test
   checklist into the build log. Do not pretend a device test passed.
7. When a library's real API differs from the plan, read the library's current source
   (Go module cache, or its repository) and adapt. Do not guess at APIs.
8. The app has no servers and sends no telemetry. Do not add any network call except
   to the messaging networks, Google Fonts is not used at runtime, and the GIF
   provider only when the user searches.
9. All source files carry `// SPDX-License-Identifier: AGPL-3.0-or-later`.
10. Material 3 Expressive is the design system. Use `MaterialExpressiveTheme` and the
    Expressive components named in `docs/UI_DESIGN.md`; do not substitute older
    components when an Expressive one exists.
11. The owner's preference: do not generate code outside the current plan step, and
    do not refactor working code unless the step calls for it.
12. **Never wait silently, and never let anything run unwatched.** The owner will not
    sit through another long silent wait on something that has frozen. These are hard
    limits, not guidelines:
    - **Every command gets a hard time limit** (`timeout N` in front of it):
      - one test class, or a few: `timeout 300` (5 minutes; normally under 1 minute);
      - full `./gradlew check`: `timeout 600` (10 minutes; normally 2 to 4 minutes);
      - `adb` commands: `timeout 60`;
      - emulator boot: wait at most 180 seconds for `sys.boot_completed` to be `1`;
      - anything else that is not instant: a limit no more than twice its normal time.
    - **Anything over 1 minute runs in the background** with its output going to a log
      file. Check the log at least **every 60 seconds** and tell the owner, in one
      line, what is running and how far along it is.
    - **Frozen means: no new output for 3 minutes, or past its normal time by half.**
      Then stop it at once (`./gradlew --stop`, or kill the process), tell the owner
      it froze and what was running, find out why, and only then try again. Never
      just wait longer, and never restart the same command unchanged.
    - **Before starting anything slow, say** what it is and how long it normally takes.
      **When it ends, say** whether it passed or failed.
    - **GitHub CI normally takes 10 to 20 minutes.** Do other useful work meanwhile. If
      it passes 30 minutes, look at the run's status and logs and tell the owner what
      is happening.
    - **Never repeat a slow full run to "see if it happens again"** without first
      telling the owner why it is needed and how long it will take.

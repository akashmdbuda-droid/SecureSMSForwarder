# Project Rules & Engineering Workflow

This repository uses an integrated **Graphify + OpenSpec + Superpowers + Versioned Release** workflow. Every coding agent and swarm worker working in this repository MUST follow these rules.

---

## 0. Session Continuity & Active Work (READ FIRST IN EVERY NEW CHAT!)

Before asking the user to repeat context or starting fresh, ALWAYS read these files first:
1. **Current Handoff & Swarm Status:** `docs/HANDOFF.md` (locked user decisions, active phase, swarm lane map, next action)
2. **Project Architecture & Known Issues:** `docs/PROJECT_CONTEXT.md`
3. **Authoritative Version Ledger:** `docs/VERSIONING.md`
4. **Active OpenSpec Changes & Source-of-Truth Specs:** `openspec/changes/` and `openspec/specs/`
5. **Knowledge Graph:** Query `graphify-out/graph.json` before broad file scanning.

Whenever you complete a milestone, lane, or session, update `docs/HANDOFF.md` and `docs/PROJECT_CONTEXT.md` (and commit+push if appropriate so fresh cloud VMs inherit the latest state).

---

## 1. Project Context & Graphify (Orient First, Keep Fresh)

1. **Read Context First:**
   - Handoff & active progress: `docs/HANDOFF.md`
   - Architecture, Firebase schema, module map, and known bugs: `docs/PROJECT_CONTEXT.md`
   - Authoritative version snapshot, release history, and drift tracking: `docs/VERSIONING.md`
   - Living source-of-truth specifications: `openspec/specs/`
2. **Query the Knowledge Graph Before Broad Searching:**
   - When `graphify-out/graph.json` exists, start codebase/architecture queries with `graphify query "<question>"`, `graphify explain "<symbol>"`, `graphify path "<A>" "<B>"`, or `graphify section "<heading>"` before scanning raw files.
   - Consult `graphify-out/GRAPH_REPORT.md` for high-level architecture orientation.
3. **Maintain Context & Graph After Every Change:**
   - Update `docs/HANDOFF.md` and `docs/PROJECT_CONTEXT.md` whenever architecture, active work, or known issues change.
   - Update `docs/VERSIONING.md` on every release.
   - Run `graphify update . --force` after modifying code or docs so `graphify-out/` remains current.

---

## 2. Superpowers + OpenSpec Engineering Workflow

Project skills live in `.agents/skills/` (Superpowers v7.0.2, OpenSpec, and Graphify). Always invoke matching skills before taking action (see `.agents/skills/using-superpowers/SKILL.md`).

### Standard Lifecycle for Features & Bug Fixes
1. **Orient & Explore:**
   - Read `docs/HANDOFF.md`, query `graphify-out/`, check `docs/PROJECT_CONTEXT.md`, and inspect relevant specs under `openspec/specs/`.
   - For bugs: invoke `systematic-debugging` (`.agents/skills/systematic-debugging/SKILL.md`) to isolate root causes before proposing fixes.
   - For ambiguous features or design tradeoffs: invoke `brainstorming` (`.agents/skills/brainstorming/SKILL.md`) or `openspec-explore`.
2. **Propose & Align (OpenSpec Planning Boundary):**
   - Create a structured OpenSpec change (`openspec new change "<kebab-name>"` / `openspec-propose`) with `proposal.md`, delta `specs/<capability>/spec.md`, `design.md`, and `tasks.md`.
   - Validate with `openspec validate --all`.
   - **Agree before coding:** Present the proposal/plan to the user and get alignment before editing application source code.
3. **Plan & Implement with Superpowers TDD (Single Agent or Swarm):**
   - Follow `writing-plans` and `test-driven-development` (RED -> GREEN -> REFACTOR) while executing `openspec-apply-change`.
   - For parallel swarm workstreams, respect the file-ownership lanes in `tasks.md` and use `dispatching-parallel-agents` / `subagent-driven-development`.
4. **Verify Before Completion:**
   - Follow `verification-before-completion`: run unit tests (`./gradlew testDebugUnitTest assembleDebug` locally or via `.github/workflows/ci.yml`) and confirm every task in `tasks.md` and every scenario in the delta specs passes with real evidence.
5. **Sync, Archive & Refresh Context:**
   - Sync delta specs into `openspec/specs/`, validate with `openspec validate --specs`, and archive the completed change (`openspec archive <change-name>`).
   - Update `docs/HANDOFF.md` and `docs/PROJECT_CONTEXT.md`, and run `graphify update . --force`.

---

## 3. Release Process after Feature Additions

Whenever a new feature or fix is added and **confirmed as working**, you MUST automatically follow this release process to deploy the update to users via GitHub Releases:

1. **Archive OpenSpec Change:** Ensure the completed change is synced to `openspec/specs/` and archived under `openspec/changes/archive/`.
2. **Bump Version:** Increase the `versionName` and `versionCode` in `app/build.gradle.kts`.
3. **Update Welcome Toast:** Update the version string in the `Toast` message in `MainActivity.kt` (or keep dynamically bound to `BuildConfig.VERSION_NAME`).
4. **Update Changelog:** Update the in-app changelog section in `app/src/main/java/com/example/securesmsforwarder/ui/changelog/Changelog.kt` with the new version details and a list of what changed.
5. **Update Context, Handoff & Versioning Ledger:** Update `docs/VERSIONING.md` (Current Version Snapshot + Release History Ledger), update `docs/PROJECT_CONTEXT.md` and `docs/HANDOFF.md`, and run `graphify update . --force`.
6. **Commit:** Commit the changes (e.g., `git commit -am "Bump version to <new_version>"`).
7. **Tag Release:** Create a git tag matching the new version name, starting with `v` (e.g., `git tag v1.1.0`).
8. **Push:** Push the commit and the tag to GitHub (e.g., `git push origin main` and `git push origin v1.1.0`).

Following these steps will trigger the `.github/workflows/release.yml` GitHub Action, which builds the signed APK and publishes the auto-update to users.

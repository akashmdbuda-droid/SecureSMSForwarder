---
name: feature-lifecycle
description: End-to-end SecureSMSForwarder workflow combining Graphify, OpenSpec, Superpowers, and versioned GitHub Releases
---

# Workflow: feature-lifecycle

1. **Orient (Graphify + Context):**
   - Run `graphify query "<topic>"` or `graphify explain "<symbol>"` against `graphify-out/graph.json`.
   - Read `docs/PROJECT_CONTEXT.md`, `docs/VERSIONING.md`, and matching specs in `openspec/specs/`.
2. **Explore / Diagnose (Superpowers):**
   - For bugs: follow `.agents/skills/systematic-debugging/SKILL.md`.
   - For features: follow `.agents/skills/brainstorming/SKILL.md` or `.agents/skills/openspec-explore/SKILL.md`.
3. **Propose (OpenSpec):**
   - Follow `.agents/skills/openspec-propose/SKILL.md` to create `openspec/changes/<change-name>/` (`proposal.md`, `specs/`, `design.md`, `tasks.md`).
   - Run `openspec validate <change-name>`.
   - Present the proposal to the user and wait for approval before modifying app code.
4. **Implement & Verify (Superpowers TDD + OpenSpec Apply):**
   - Follow `.agents/skills/openspec-apply-change/SKILL.md`, `.agents/skills/test-driven-development/SKILL.md`, and `.agents/skills/verification-before-completion/SKILL.md`.
   - Run `graphify update .` after editing code.
5. **Archive & Release (OpenSpec Archive + Versioning):**
   - Sync specs and run `openspec archive <change-name>` + `openspec validate --specs`.
   - Once confirmed as working, execute the Release Process in `.agents/AGENTS.md` and `docs/VERSIONING.md` (bump version, update toast & `Changelog.kt`, update `docs/VERSIONING.md` & `docs/PROJECT_CONTEXT.md`, run `graphify update .`, commit, tag `v<version>`, push).

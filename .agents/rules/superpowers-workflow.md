---
trigger: always_on
description: Enforce Superpowers + OpenSpec + Versioning workflow across all tasks in SecureSMSForwarder.
---

## Superpowers + OpenSpec Workflow

1. **Bootstrap:** At the start of any task, check `.agents/skills/using-superpowers/SKILL.md` and invoke applicable process skills (`brainstorming`, `systematic-debugging`, `writing-plans`, `test-driven-development`, `verification-before-completion`) before jumping into code.
2. **Spec-Driven Changes:** Non-trivial feature additions, architectural changes, and multi-file bug fixes MUST be proposed as an OpenSpec change under `openspec/changes/<name>/` and validated with `openspec validate` before implementation.
3. **Context & Versioning Discipline:**
   - Treat `docs/PROJECT_CONTEXT.md`, `docs/VERSIONING.md`, `openspec/specs/`, and `graphify-out/` as first-class living artifacts.
   - Keep `app/build.gradle.kts` (`versionCode`, `versionName`), `MainActivity.kt`, `Changelog.kt`, `docs/VERSIONING.md`, and git tags strictly synchronized on every release.
4. **Graph Freshness:** After modifying code or documentation, run `graphify update .` so `graphify-out/` reflects the latest codebase state.

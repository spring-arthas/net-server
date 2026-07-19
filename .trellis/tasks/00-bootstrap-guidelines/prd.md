# Bootstrap: Fill Project Development Guidelines

## Purpose

Welcome to Trellis! This is your first task.

AI agents use `.trellis/spec/` to understand YOUR project's coding conventions.
**Empty templates = AI writes generic code that doesn't match your project style.**

Filling these guidelines is a one-time setup that pays off for every future AI session.

---

## Your Task

Fill in the guideline files based on your **existing codebase**.


### Backend Guidelines

| File | What to Document |
|------|------------------|
| `.trellis/spec/backend/directory-structure.md` | Where different file types go (routes, services, utils) |
| `.trellis/spec/backend/database-guidelines.md` | ORM, migrations, query patterns, naming conventions |
| `.trellis/spec/backend/error-handling.md` | How errors are caught, logged, and returned |
| `.trellis/spec/backend/logging-guidelines.md` | Log levels, format, what to log |
| `.trellis/spec/backend/quality-guidelines.md` | Code review standards, testing requirements |


### Thinking Guides (Optional)

The `.trellis/spec/guides/` directory contains thinking guides that are already
filled with general best practices. You can customize them for your project if needed.

---

## How to Fill Guidelines

### Step 0: Import from Existing Specs (Recommended)

Many projects already have coding conventions documented. **Check these first** before writing from scratch:

| File / Directory | Tool |
|------|------|
| `CLAUDE.md` / `CLAUDE.local.md` | Claude Code |
| `AGENTS.md` | Claude Code |
| `.cursorrules` | Cursor |
| `.cursor/rules/*.mdc` | Cursor (rules directory) |
| `.windsurfrules` | Windsurf |
| `.clinerules` | Cline |
| `.roomodes` | Roo Code |
| `.github/copilot-instructions.md` | GitHub Copilot |
| `.vscode/settings.json` → `github.copilot.chat.codeGeneration.instructions` | VS Code Copilot |
| `CONVENTIONS.md` / `.aider.conf.yml` | aider |
| `CONTRIBUTING.md` | General project conventions |
| `.editorconfig` | Editor formatting rules |

If any of these exist, read them first and extract the relevant coding conventions into the corresponding `.trellis/spec/` files. This saves significant effort compared to writing everything from scratch.

### Step 1: Analyze the Codebase

Ask AI to help discover patterns from actual code:

- "Read all existing config files (CLAUDE.md, .cursorrules, etc.) and extract coding conventions into .trellis/spec/"
- "Analyze my codebase and document the patterns you see"
- "Find error handling / component / API patterns and document them"

### Step 2: Document Reality, Not Ideals

Write what your codebase **actually does**, not what you wish it did.
AI needs to match existing patterns, not introduce new ones.

- **Look at existing code** - Find 2-3 examples of each pattern
- **Include file paths** - Reference real files as examples
- **List anti-patterns** - What does your team avoid?

---

## Status - ✅ COMPLETE

**All 5 backend guidelines filled with real code examples:**

✅ **directory-structure.md** - 4-layer repository pattern, NIO handler architecture, module organization rules, anti-patterns
✅ **database-guidelines.md** - Custom lightweight ORM, query patterns (auto-generated + XML), DO rules, naming conventions, soft delete
✅ **error-handling.md** - Exception types (RuntimeException, IllegalArgumentException), logging patterns, layer-specific handling, common mistakes
✅ **logging-guidelines.md** - SLF4J with @Slf4j, log levels (DEBUG/INFO/WARN/ERROR), structured format, what to log/avoid
✅ **quality-guidelines.md** - Forbidden patterns, required patterns, testing requirements, code review checklist

## Completion Checklist

- [x] directory-structure.md - COMPLETE
- [x] database-guidelines.md - COMPLETE
- [x] error-handling.md - COMPLETE
- [x] logging-guidelines.md - COMPLETE
- [x] quality-guidelines.md - COMPLETE

**Total lines of documentation added:** ~2,500+ lines across 5 files

**All files now include:**
- Clear descriptions and rationale
- Real code examples from the actual codebase
- Anti-patterns with fixes
- Naming conventions with tables
- Common mistakes and corrections

When done:

```bash
python3 ./.trellis/scripts/task.py finish
python3 ./.trellis/scripts/task.py archive 00-bootstrap-guidelines
```

---

## Why This Matters

After completing this task:

1. AI will write code that matches your project style
2. Relevant `/trellis:before-*-dev` commands will inject real context
3. `/trellis:check-*` commands will validate against your actual standards
4. Future developers (human or AI) will onboard faster

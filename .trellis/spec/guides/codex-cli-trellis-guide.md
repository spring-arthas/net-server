# Codex CLI Trellis Guide

> Purpose: make the repository's Trellis workflow directly usable from Codex CLI.

---

## Why This Guide Exists

This repository already includes Trellis workflow files and Claude-specific command
definitions, but Codex CLI does not execute those slash commands directly.

For Codex CLI, use the Python scripts under `.trellis/scripts/` instead.

---

## Session Start

Use this command at the beginning of a Codex session:

```bash
python3 ./.trellis/scripts/codex_start.py --scope backend
```

If developer identity is not initialized yet:

```bash
python3 ./.trellis/scripts/codex_start.py --developer <your-name> --scope backend
```

What it does:

- Verifies or initializes `.trellis/.developer`
- Prints the current Trellis session context
- Points Codex to the relevant Trellis guides
- Lists the most common task commands

---

## Command Mapping

| Trellis intent | Codex CLI command |
|---|---|
| Start session | `python3 ./.trellis/scripts/codex_start.py --scope backend` |
| Initialize developer | `python3 ./.trellis/scripts/init_developer.py <name>` |
| Show developer | `python3 ./.trellis/scripts/get_developer.py` |
| Show session context | `python3 ./.trellis/scripts/get_context.py` |
| List tasks | `python3 ./.trellis/scripts/task.py list` |
| List my tasks | `python3 ./.trellis/scripts/task.py list --mine` |
| Create task | `python3 ./.trellis/scripts/task.py create "<title>" --slug <slug>` |
| Start task | `python3 ./.trellis/scripts/task.py start <task-dir>` |
| Finish current task | `python3 ./.trellis/scripts/task.py finish` |
| Set task branch | `python3 ./.trellis/scripts/task.py set-branch <task-dir> <branch>` |
| Set task scope | `python3 ./.trellis/scripts/task.py set-scope <task-dir> <scope>` |

---

## Scope Recommendations

- `--scope backend`: Java NIO, Spring XML, MyBatis, protocol frames, handlers
- `--scope frontend`: frontend development in repositories that also contain frontend code
- `--scope cross-layer`: features spanning multiple layers or protocol/data flow changes
- `--scope general`: only workflow and Codex/Trellis mapping

---

## Recommended Codex Workflow

1. Run `python3 ./.trellis/scripts/codex_start.py --scope backend`
2. Read the recommended guide files printed by the command
3. Use `python3 ./.trellis/scripts/task.py list --mine` to find assigned work
4. Use `python3 ./.trellis/scripts/task.py start <task-dir>` before implementation
5. After work is done, use `python3 ./.trellis/scripts/task.py finish`

---

## Notes

- Claude-specific command definitions in `.claude/commands.json` remain valid for Claude Code
- Codex CLI should rely on `AGENTS.md` plus the scripts in `.trellis/scripts/`
- Keep this guide updated when Trellis scripts or workflow conventions change

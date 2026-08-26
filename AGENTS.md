<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

Use the `/trellis:start` command when starting a new session to:
- Initialize your developer identity
- Understand current project context
- Read relevant guidelines

Use `@/.trellis/` to learn:
- Development workflow (`workflow.md`)
- Project structure guidelines (`spec/`)
- Developer workspace (`workspace/`)

Keep this managed block so 'trellis update' can refresh the instructions.

<!-- TRELLIS:END -->

## Codex CLI Compatibility

For Codex CLI sessions, the Trellis workflow in this repository is supported via
the local scripts under `.trellis/scripts/`.

- Treat `/trellis:start` as `python3 ./.trellis/scripts/codex_start.py --scope backend`
- If developer identity is missing, initialize it with `python3 ./.trellis/scripts/init_developer.py <name>`
- Use `python3 ./.trellis/scripts/get_context.py` to inspect session context
- Use `python3 ./.trellis/scripts/task.py` to manage Trellis tasks
- Read `.trellis/spec/guides/codex-cli-trellis-guide.md` for command mapping details

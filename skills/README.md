# FlowScope agent skills

Skills that teach a coding agent how to work with FlowScope in a project that uses it.

| Skill | Use it to |
|---|---|
| [`flowscope-tests`](flowscope-tests/SKILL.md) | Have an agent write, validate, run and fix `.flow` tests and suites. |

## Claude Code

Put the folder in the project (shared with the team through Git) or in your home directory. Without
a clone of this repository, download the copy that matches your installed CLI:

```bash
v=$(flowscope version | awk '{print $2}')
mkdir -p .claude/skills/flowscope-tests
for f in SKILL.md reference.md; do
  curl -fsSL -o ".claude/skills/flowscope-tests/$f" \
    "https://raw.githubusercontent.com/FlowScopeDev/flowscope/v$v/skills/flowscope-tests/$f"
done
# or, for every project: ~/.claude/skills/flowscope-tests
```

From a clone, copy it instead:

```bash
mkdir -p .claude/skills
cp -R /path/to/flowscope/skills/flowscope-tests .claude/skills/
```

Every CLI command is listed in [CLI.md](../CLI.md).

Claude loads it when you ask for a UI test ("add a test for the login screen"), or run it
explicitly with `/flowscope-tests`.

## Codex and other agents

Copy the same folder somewhere in the repository, for example `docs/flowscope-tests/`, and point the
agent at it from `AGENTS.md`:

```markdown
## UI tests

Android UI tests are FlowScope `.flow` files in `flows/tests/`. Before writing or changing one, read
`docs/flowscope-tests/SKILL.md` and `docs/flowscope-tests/reference.md`, and follow the workflow there.
```

`SKILL.md` is plain Markdown, so any agent that can read files can follow it.

## Requirements

The `flowscope` CLI on `PATH` (build it with `./gradlew :recorder-host:installDist`; see the main README), a project created
with `flowscope project init`, and, to run tests rather than only validate them, a device or emulator
attached over `adb`.

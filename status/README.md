# Status

What happened, one file per entry: `status/<date>-<name>.md`, where the name is the branch without `worktree-` (the
overnight report uses `overnight`). Each branch writes only its own file, so two branches recording at once never
touch the same lines and their landings cannot conflict. `build-plan.md`'s section 8 holds the entries up to
2026-11-15; since then they are here, in date order by name.

An entry starts `# <date>, <what>` and says what changed, what was checked and how (the commands and the counts), and
what is not covered. Keep it short: the commits hold the detail. A branch that records twice on one day adds to its
own file.

Never add an entry to `build-plan.md`. Edit its other sections only for what they say: a milestone's state, a risk, an
open question.

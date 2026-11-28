# 2026-11-27: demo.sh's flag_tables on a fresh world; the scale file read

Branch `fix-scale-read`, two fix-ups found resetting the world for item 321.

- `flag_tables` (item 318) had gary set rules on every flagged table, but on a fresh world `brokerage.party.beneficial_owners`
  is unclassified by design (D27), so closed, and the catalog refused its rules with 403: `brokerage_backoffice_governance`
  stopped there, leaving payments without its rules, the owners unset and aml_cases without its hold, and `gen` warned.
  Item 318's live check had not met it because stella had classified the table first. A table with no classification of
  its own now gets its flag, is named, and gets its rules at the first `gen` after a steward classifies it. Run live on
  the new world: governed, payments with both rules, the hold placed.
- `demo.sh` printed "No such file or directory" for `~/.nuna-demo/scale` before a scale was set (the `<` came before
  `2>/dev/null`).

Left open: between a steward classifying beneficial_owners and the next `gen`, the table has its flag but no rules, so
it masks nothing.

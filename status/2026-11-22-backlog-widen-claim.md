# 2026-11-22: the holder of a claim can widen it

`./backlog.sh claim <n> <area>...` run by the holder of item n now widens its claim. The new claim covers the areas it
already held plus the new ones, and it is checked against every other claim as a new claim is. Before this,
widening meant releasing the claim and claiming again, which left a moment when another agent could take the item.
In the console build, the auto-mode safety check refused that release for items 125 and 129. Paul chose the fix on
2026-11-22: "Fix backlog.sh, then widen". `test-backlog.sh` checks three cases:
- the holder widens its claim;
- a widening into another claim's area is refused and leaves the claim as it was;
- another session cannot widen a claim it does not hold.

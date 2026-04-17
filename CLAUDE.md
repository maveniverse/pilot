# Pilot — Claude Code Instructions

## Build

Use `./mvnw` instead of `mvn` for all Maven commands. Always add `-B` (batch mode).

```bash
./mvnw compile -B
./mvnw test -B
```

## After Creating a PR

After pushing a PR, monitor and address all automated feedback:

1. **CI**: Check `gh pr checks <number>` — fix any failures, push, and re-check.
2. **SonarCloud**: Fetch issues via `https://sonarcloud.io/api/issues/search?componentKeys=maveniverse_pilot&pullRequest=<number>&statuses=OPEN,CONFIRMED&sinceLeakPeriod=true`. Fix all issues, or explain in a comment why a specific issue is a false positive.
3. **CodeRabbit**: Review all CodeRabbit inline comments. For each comment:
   - If actionable: fix the issue, push, and reply to the comment thread confirming the fix (include commit SHA).
   - If incorrect/not applicable: reply explaining why the suggestion doesn't apply.
   - Use `_Claude Code on behalf of Guillaume Nodet_` attribution in all replies.

Repeat until CI, SonarCloud, and CodeRabbit are all clean.

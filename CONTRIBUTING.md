# Contributing

Thanks for helping. Bug reports, fixes and small improvements are welcome.

## Build and test

You need Android Studio (JDK 17) and Android SDK 37. Create a gitignored `local.properties` with your `sdk.dir`, then run:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
scripts/verify_no_history_queries.sh   # needs ripgrep
```

Add a unit test for any change to authorization, parsing, rate limiting or receipts.

## Safety rules a change must not weaken

These are listed under "Safety invariants" in [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md). In short:

- No reading of SMS/MMS history beyond the one approved, event-triggered MMS read, and no `ContentObserver`.
- The hard email caps (20/minute, 300/hour, 450/day), the SMS cap and the circuit breaker stay on.
- A tagged reply is texted only if it matches a forward this installation sent. A command is accepted only from an authorized address that passed Gmail's DMARC check, using that address's own permission.
- Anything exported to other apps must not change forwarding state.

If a change seems to need loosening one of these, open an issue first.

## Privacy

Do not put real phone numbers, email addresses, message text, tokens or screenshots of your own data in issues, pull requests, tests or commit messages. Use made-up values such as `example.com` and `+15551234567`.

## Pull requests

Keep them small and focused. Update the README, `docs/` and `CHANGELOG.md` when behavior changes. By contributing you agree your work is released under the [0BSD license](LICENSE).

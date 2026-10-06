# Security policy

## Supported versions

Only the latest release on `main` is supported.

## Reporting a vulnerability

Please report privately: open the **Security** tab of this repository and choose **Report a vulnerability**. Do not open a public issue for anything that could let someone else control a user's forwarding, send texts, or read messages.

Include the app version, what you did, and what happened. Please do not include real message contents, phone numbers or email addresses; use made-up ones.

This is a spare-time project, so replies are best effort.

## What is in scope

- Anything that lets someone who is not on the Remote control allowlist change forwarding, send a text, or cause an email to be sent.
- Weaknesses in how the sender is authenticated (Gmail's DMARC result) or in the rate limits and circuit breaker.
- Components other apps can reach (exported receivers, services or activities).
- Message content or addresses stored or logged where they should not be.

## Out of scope

- Your organization's rules about personal email or messages at work.
- Carrier or Android behavior the app cannot control (see "Good to Know" in the README).
- Problems that need physical access to an unlocked phone.

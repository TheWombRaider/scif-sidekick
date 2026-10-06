#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if ! command -v rg >/dev/null 2>&1; then
  echo "FAIL: ripgrep (rg) is not installed -- this check cannot silently report PASS without it" >&2
  exit 2
fi

# Product approved one narrowly-scoped, event-triggered exception (see MmsContentFetcher.kt's own
# doc comment, docs/DESIGN_NOTES.md "Important Android MMS boundary", and docs/QA_AUDIT.md release-gate #5):
# reading the single content://mms row a live WAP_PUSH_RECEIVED broadcast just announced. That file
# is the only permitted call site. A ContentObserver is never permitted anywhere -- that would mean
# polling/watching history rather than reacting to one already-announced message -- and no other
# file may reference the SMS/MMS content provider at all.
allowed_file="app/src/main/java/com/scifsidekick/cleanroom/messaging/MmsContentFetcher.kt"

# Strips a `path:line:` prefix from one `rg -n` result line, then reports whether what's left is
# actual code (exit 0) or just a comment mentioning the term in prose (exit 1) -- both
# MmsContentFetcher.kt and IncomingMessageReceiver.kt deliberately explain this constraint in their
# own doc comments, and those mentions must never trip this check.
is_real_code_hit() {
  content=$(printf '%s' "$1" | sed -E 's/^.*:[0-9]+://')
  trimmed=$(printf '%s' "$content" | sed -E 's/^[[:space:]]*//')
  case "$trimmed" in
    '//'* | '*'* | '/**'* | '<!--'*) return 1 ;;
    *) return 0 ;;
  esac
}

# Deliberately a suffix match, not a "$root/$allowed_file" prefix match: on Windows, Git Bash
# rewrites a /c/... argument to C:/... before a native exe like rg.exe ever sees it, while rg then
# reports discovered subdirectories with backslashes -- so neither rg's drive-letter form nor its
# separator style reliably matches how $root was computed from `pwd`. A path can't otherwise
# contain "/$allowed_file:" (with allowed_file's own forward slashes) except as its own true
# suffix, so this is unambiguous without needing both sides in the same representation.
is_allowed_file_hit() {
  normalized=$(printf '%s' "$1" | tr '\\' '/')
  case "$normalized" in
    */"$allowed_file":*) return 0 ;;
    *) return 1 ;;
  esac
}

observer_hits=$(rg -n 'ContentObserver' "$root/app/src/main" || true)
real_observer_hits=""
if [ -n "$observer_hits" ]; then
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    if is_real_code_hit "$hit"; then
      real_observer_hits="$real_observer_hits
$hit"
    fi
  done <<EOF
$observer_hits
EOF
fi
if [ -n "$real_observer_hits" ]; then
  echo "FAIL: a ContentObserver was found -- history watching/polling is never permitted" >&2
  echo "$real_observer_hits" >&2
  exit 1
fi

provider_hits=$(rg -n 'Telephony\.(Sms|Mms)\.(CONTENT_URI|Inbox)|content://(sms|mms)' "$root/app/src/main" || true)
violations=""
if [ -n "$provider_hits" ]; then
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    if is_allowed_file_hit "$hit"; then continue; fi
    if is_real_code_hit "$hit"; then
      violations="$violations
$hit"
    fi
  done <<EOF
$provider_hits
EOF
fi
if [ -n "$violations" ]; then
  echo "FAIL: a prohibited Telephony history/query primitive was found outside $allowed_file" >&2
  echo "$violations" >&2
  exit 1
fi

if ! rg -q 'content://mms' "$root/$allowed_file" 2>/dev/null; then
  echo "FAIL: $allowed_file no longer contains the expected narrowly-scoped MMS read -- update this script if that file moved or was removed" >&2
  exit 1
fi

echo "PASS: no Telephony SMS/MMS history URI outside the one approved event-triggered read, and no ContentObserver anywhere"

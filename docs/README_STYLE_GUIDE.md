# README style guide

How the README is written and laid out. It is distilled from the README of [OmniRoute](https://github.com/diegosouzapw/OmniRoute) (MIT licensed, read on 2026-10-14), which the owner chose as the model, and adapted to this project. Where SCIF Sidekick's existing README already does something its own way, the existing way wins and is noted below.

The README is the short version. Reasoning, safety invariants and per-release detail live in `docs/` and `CHANGELOG.md`, and the README links to them instead of repeating them.

## What OmniRoute does, and we follow

**Hero block first.** A centered block opens the page: product name as the single H1 with a leading emoji, a one-line tagline in bold, then a short paragraph saying what it is and who it is for. Screenshots follow, centered, with a caption line under them in `<sub>`.

**Badges and a nav row.** A row of flat shields.io badges (version, platform, license, key technologies), then one row of anchor links to the main sections, so a reader can jump straight to setup.

**Every heading carries one leading emoji.** H1, H2 and H3 alike, one emoji each, picked to mean the section (rocket for Quick Start, stethoscope for Troubleshooting, and so on). Never two emoji, never a trailing one.

**Tables for anything that is a list of parallel things.**
- Features: three columns, `emoji | **Bold name** | description`. The name is bold and short; the description is one or two sentences.
- Troubleshooting: `Symptom | Check`, the symptom written the way a user would say it.
- Reference material (tech stack, documents): two columns, name then description, with the name in bold or as the link.
- A table cell holds one idea. If a cell needs a paragraph, it belongs in `docs/` with a link.

**Lead with the point.** A section opens with what the reader gets or does, then the detail. A key claim goes in a blockquote near the top of its section.

**Quick Start is a short numbered path to a working result.** Each step is one action in bold, with the command or setting shown exactly, then one line saying what you should see. A common snag gets a blockquote tip with an emoji and a link to Troubleshooting, not a paragraph in the middle of the steps.

**Callouts are blockquotes with an emoji.** Tips, warnings and platform notes ("Samsung?") use `> 📱 **Samsung?** ...`. A disclaimer or safety notice is a blockquote too, in bold, near the top.

**Footnote links go in `<sub>`.** A line of small text under a section points to the deeper document: `<sub>📖 [Design notes](docs/DESIGN_NOTES.md)</sub>`.

**Numbers are honest and checkable.** Figures in the README are ones the project can reproduce. A figure that changes (counts, limits, supported versions) says what it was measured against and where the method is written down, and is updated when it moves, in either direction. No rounded-up best case.

**Images have real alt text.** Describe what the image shows and why it is there, in a sentence, not "screenshot".

**Fixed order of sections, license last.** Hero, badges and nav, screenshots, features, quick start, how to use it, setup guides, build and install, troubleshooting, limits, docs index, license. Sections are separated by `---` and kept in that order so a reader always knows where to look.

**A documentation index near the end.** Every document in `docs/` is linked once, with one line saying what it is for.

## What we keep from the existing SCIF Sidekick README

- Quick Start is a plain numbered list, not `**1)**` headings, and sections are separated with `---`.
- The Docs section is a bullet list of `[path](path): what it is`.
- Troubleshooting stays `Symptom | Check`.
- The disclaimer that this is not an official or approved tool stays as a blockquote under the hero text.
- The README is written for the person who owns the phone: second person, plain words, no jargon unless the app itself uses the word.

## What we deliberately do not copy

OmniRoute is a growing, promotional project, and parts of its README do not suit a small personal tool. This is a judgement call by the assistant; the owner can reverse any of it.

- No star, sponsor, follower, contributor-wall, star-history or community-chat blocks.
- No hype wording ("flagship", "never stop coding", "the promise") and no emoji stacked in running text.
- No large headline statistic that cannot be re-measured from the repo.
- No comparison table against other products.
- No dozens of badges; keep to the row already there.
- No animated diagrams for their own sake. A screenshot or diagram earns its place by explaining something the text cannot.

## Checklist when a change touches the README

1. Version badge matches `versionName` in `app/build.gradle.kts`.
2. A new user-visible capability gets one Features row, in the same shape as the rows around it, no longer than two sentences.
3. A new thing that can go wrong, or a setting a user will look for, gets one Troubleshooting row.
4. A change to first-time setup updates Quick Start and the matching setup guide.
5. A new document in `docs/` is added to the Docs section.
6. Every new heading has exactly one leading emoji; every new table keeps its column shape.
7. Detail that explains why goes in `docs/` or the changelog, with a link, not in the README.
8. Read the finished row aloud next to its neighbours. If it is noticeably longer or more heavily qualified, cut it.

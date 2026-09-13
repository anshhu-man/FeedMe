# FeedMe UI collection

**Your kitchen. Your rules.**

## Brand rename

The active app name is **FeedMe**. This is a name-only update: the concept, layouts, 54-feature blueprint, 98 screens and 900 canonical action bindings remain intact. The `biteclub_ui` and `biteclub_blueprint` directory names are legacy paths retained so existing links and preview servers continue to work. Earlier BiteClub ZIP archives remain unchanged historical snapshots. `ASSET_MANIFEST.md` preserves the original image-generation prompts and provenance verbatim; its former brand references are intentional.

[Open the interactive UI](index.html). Start at signup, explore any screen from the navigator, or switch to **All 98 screens** for the complete visual gallery. iOS/Android preview controls change the device presentation; staff tools use a desktop layout.

## What is here

- 98 individually designed screens, joined to the original screen/action registry.
- 900 canonical button bindings, including shared navigation and conditional controls.
- An interactive prototype with validation, contextual pickers, confirmations and simulated failure/pending states.
- A PNG export of every screen in `screens/`.
- [Design language and Kotlin handoff](DESIGN_SYSTEM.md).
- [Kotlin design tokens](tokens/FeedMeTokens.kt).
- [Original food-image prompts and provenance](ASSET_MANIFEST.md).
- [Automated QA receipt](verification/report.json).

All images and code are included; no CDN is required. Extract the full folder before opening index.html so local image links remain intact. Existing production plans are preserved separately in `../biteclub_blueprint`.

## Source layout

`src/core.js` owns shared components. `views-access.js`, `views-kitchen.js`, `views-social.js` and `views-systems.js` contain explicit per-screen designs. `src/app.js` provides the design browser and local interaction simulator. CSS is separated by shared tokens and domain treatment. `scripts/build.mjs` embeds the sources and copies the canonical registry.

## Rebuild and browser checks

From this directory:

```sh
node scripts/build.mjs
node scripts/qa.mjs
```

The QA script needs Playwright plus Chrome/Chromium. Set `FEEDME_PLAYWRIGHT_PATH` or `FEEDME_BROWSER_PATH` to use another installed runtime. It launches an isolated temporary profile, blocks external page requests, captures every screen and writes `verification/report.json`.

This is a high-fidelity front-end design prototype, **not** a deployed application or compiled Kotlin app. Sign-in, posts, payment access and server responses are simulated. Use made-up credentials only. Native accessibility, localization, backend integration, authorization and real-device testing remain production work.

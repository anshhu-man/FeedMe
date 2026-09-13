# FeedMe — Your kitchen. Your rules.

High-fidelity interface design for all 98 screens in the production blueprint. The implementation is a responsive HTML/CSS/JavaScript prototype with synthetic data; it does not replace the proposed Android/iOS Kotlin clients or implement the backend.

## The visual idea

A kitchen club with the confidence of an independent food magazine: oversized type, honest food photography, expressive color blocks, little tilted labels, and room to breathe. Healthy cooking feels approachable, not like another dashboard to maintain. Make Mine gets its own memorable typographic moment. Social feels intimate and casual, without follower competition or public food scores.

This is an original FeedMe identity. It does not reproduce Fight Club artwork or the Snapchat/Instagram interfaces.

## Color roles

| Token | Value | Role |
| --- | --- | --- |
| electricBlue | #304FFE | Primary actions, expressive posters, active navigation |
| acidLime | #D4FF5A | Selected choices, helpful highlights, small celebratory labels |
| warmCoral | #FF7657 | Warm social accents, illustrations, secondary personality |
| softLilac | #D7C9FF | Calm personalization, profiles and supporting surfaces |
| ink | #161917 | Primary text and high-contrast structure |
| paper | #F7F7F2 | Warm everyday canvas |
| muted | #60665E | Secondary body text |
| line | #E4E6DF | Quiet grouping and separators |

Use white text on electric blue and dark ink on lime, lilac and coral. Red/error text is reserved for real failure or destructive choices. Color is never the only expression of status.

## Typography and spacing

System sans-serif fonts keep the offline package independent of remote font services. The prototype uses Arial/Helvetica with heavy, tightly tracked headlines. An expressive serif is used selectively in the cooking domain for a handwritten/editorial feel, not for form controls.

| Role | Typical size | Behavior |
| --- | --- | --- |
| Campaign/poster | 48–84px | Very short phrases, explicit wrapping, decorative scope only |
| Screen heading | 32–46px | 1–3 lines, tight leading |
| Section heading | 17–24px | Clear content groups |
| Body | 13–16px | Comfortable line spacing |
| Input | 14px | Real labelled native controls in this browser prototype |
| Metadata | 10–12px | Secondary context only; critical instructions use body size |

Base spacing scale: 4, 8, 12, 16, 20, 24, 32. Phone content has 22px horizontal padding. Standard cards use 20–25px radii, buttons 14px, native-feeling pills and avatars use full rounding. Buttons target at least 44px in touchable regions. Native implementations must use scalable sp typography, dp geometry, accessibility labels and device font scaling rather than copying browser pixels blindly.

## Layout families

- **Entry:** expressive blue welcome poster; fast, legible identity forms; private-by-default onboarding.
- **Kitchen:** food editorial, compact effort setup, source-aware Make Mine comparisons, large cooking steps and timers.
- **Cookbook/memory:** private scrapbook grids, reusable collection covers and plain-language memory controls.
- **Social:** close-circle meal feed, full-bleed stories, lasting Plate grid and prominent Make Mine handoff.
- **Publishing:** photo-first composition with separate recipe, audience and private-copy permissions.
- **Conversations/shared meals:** useful contextual cards, quiet private replies, explicit participation and contributions.
- **Settings/safety:** calmer spacing, clear labels, consequence summaries, no playful ambiguity around deletion or permissions.
- **Membership:** optional value and provisional purchase states; no fabricated prices or auto-confirmed entitlement.
- **Operations:** 900px desktop console with tables, queues, reviewer evidence and scoped actions. Staff sign-in remains separate.

## Interaction and accessibility

Every canonical screen has an explicit renderer. The canonical 900 action bindings remain available through primary controls, content cards, bottom navigation or the More options disclosure. Conditional owner/picker/composer controls follow the original mode; the design inspector can reveal all role-specific controls for review. A gallery selection changes only the preview, not an account or resource.

Use real buttons, inputs, selects, checkboxes and details disclosures; no clickable anonymous divs. Focus is visible. Icons have text or accessible names. Reduced-motion preference removes decorative transitions. Internal scrolling is bounded to the phone content region and does not obscure bottom navigation. Native implementations still need VoiceOver/TalkBack, larger text, localization and device testing; browser checks are not a substitute.

Screen capture exports show the first viewport of each screen. Longer forms and secondary actions remain scrollable in the interactive prototype. The asset gallery is lazy-loaded. All source code and images are local; no third-party CDN is required.

## Kotlin handoff

`tokens/FeedMeTokens.kt` contains dependency-free Kotlin constants for common code. Map ARGB values to Compose colors; translate geometry to dp and typography to sp within the existing Kotlin Multiplatform design-system module. Use semantic components such as PrimaryButton, FoodCard, MealStory, AudienceChoice, RecipeAttachment and ConfirmationSheet, not screen-specific copies of basic controls.

The screen IDs and action IDs in `data/screen_registry.json` are stable joins to the production blueprint’s navigation and API contracts. Keep visual renderers separate from domain logic: ViewModel state selects the screen/state; use cases dispatch the canonical operation; native adapters own camera, provider auth, notifications and store UI. Browser fixture rendering must never become production evidence of authorization, food review or purchase completion.

## Images and content

Three original food photographs were generated with the built-in image-generation tool and saved in `assets`. Exact prompts, source paths and inspection notes are in `ASSET_MANIFEST.md`. Meals, names, messages, numbers and receipts are illustration fixtures. Photos do not establish ingredients, nutrition, review status or preparation safety.

## Verification boundary

The automated browser suite checks every renderer, every canonical action’s presence, missing bindings, image loads, constrained layouts, and selected end-to-end simulated flows. It exports a PNG of every screen. Separate native, backend, authorization, store, food-review and operational tests remain necessary before a real release.

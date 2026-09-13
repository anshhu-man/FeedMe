# FeedMe production blueprint

**See a plate. Make it yours.**

Android + iOS with shared Kotlin, confirmed by the user. This package specifies every proposed feature and the production support required to operate it. It contains plans and a simulated local explorer, not a deployed application.

## Start here

- [Interactive blueprint, screen maps and clickable navigation](index.html) — starts at signup/login; choose a screen, click its controls, and inspect its API/effects/failure paths.
- [Product strategy](01_Product_Strategy.md)
- [Implementation roadmap](08_Implementation_Roadmap.md)
- [System architecture](architecture/02_Architecture.md)
- [Data model](architecture/03_Data_Model.md)
- [OpenAPI contract](architecture/04_API_Contract.json)
- [Events](architecture/05_Events.md)
- [Design system](06_Design_System.md)
- [Every screen and button](07_Screens_and_Interactions.md)
- [Operations runbooks](architecture/09_Production_Runbooks.md)
- [Security and privacy](architecture/10_Security_Privacy.md)
- [Sources and decisions](architecture/11_Sources_Decisions.md)
- [Integration guide](12_Integration_Guide.md)

## Coverage

54 feature specifications, 98 application/operational screens, 900 button bindings (including inherited navigation), and 201 API operations. Later and optional paid features have full designs; phase labels determine execution order, not documentation completeness.

## Maps and machine-readable contracts

- [All screens mind map](maps/all_screens_mindmap.svg), [Mermaid source](maps/all_screens_mindmap.mmd)
- [All screens architecture](maps/all_screens_architecture.svg), [Mermaid source](maps/all_screens_architecture.mmd)
- [All button transitions Mermaid](maps/all_button_navigation.mmd)
- [Button action ledger CSV](registry/button_actions.csv)
- [Feature traceability CSV](registry/feature_traceability.csv)
- [Screen registry JSON](registry/screen_registry.json)
- [Verification report](verification/report.md)

## Feature documents

| ID | Feature | Phase |
| --- | --- | --- |
| [F01](features/F01.md) | Make Mine | P1 |
| [F02](features/F02.md) | Smart Meal Helper | P1 |
| [F03](features/F03.md) | Use What I Have | P1 |
| [F04](features/F04.md) | Food Preferences | P1 |
| [F05](features/F05.md) | Match My Energy | P1 |
| [F06](features/F06.md) | Taste and Texture | P1 |
| [F07](features/F07.md) | Cook Assemble Improve | P1 |
| [F08](features/F08.md) | Make It Easier | P1 |
| [F09](features/F09.md) | Something Else | P1 |
| [F10](features/F10.md) | Keep the Vibe | P1 |
| [F11](features/F11.md) | Effort Preview | P1 |
| [F12](features/F12.md) | Guided Cooking | P1 |
| [F13](features/F13.md) | Reviewed Recipe Library | P1 |
| [F14](features/F14.md) | Make Again | P1 |
| [F15](features/F15.md) | Optional Feedback | P1 |
| [F16](features/F16.md) | Taste and Effort Memory | P1 |
| [F17](features/F17.md) | Explain My Suggestion | P1 |
| [F18](features/F18.md) | Editable Memories | P1 |
| [F19](features/F19.md) | Private Cookbook | P1 |
| [F20](features/F20.md) | Use It Again | P1 |
| [F21](features/F21.md) | Today | P1 |
| [F22](features/F22.md) | My Plate | P1 |
| [F23](features/F23.md) | Kitchen Circles | P1 |
| [F24](features/F24.md) | Recipe Attachments | P1 |
| [F25](features/F25.md) | Your Take | P1 |
| [F26](features/F26.md) | Remix Trail | P1 |
| [F27](features/F27.md) | Reactions | P1 |
| [F28](features/F28.md) | Private Replies | P1 |
| [F29](features/F29.md) | Ask for Recipe | P1 |
| [F30](features/F30.md) | Recipe Saving Permissions | P1 |
| [F31](features/F31.md) | Share Composer | P1 |
| [F32](features/F32.md) | Fridge SOS | P2 |
| [F33](features/F33.md) | Tonight | P2 |
| [F34](features/F34.md) | Dinner Pact | P3 |
| [F35](features/F35.md) | Bring a Bit | P3 |
| [F36](features/F36.md) | Shortcut Swap | P3 |
| [F37](features/F37.md) | Dinner Vote | P3 |
| [F38](features/F38.md) | Short Clips | P3 |
| [F39](features/F39.md) | Private Cooking Preferences | P1 |
| [F40](features/F40.md) | Audience Controls | P1 |
| [F41](features/F41.md) | Notifications | P1 |
| [F42](features/F42.md) | Delete Mute Block Report | P1 |
| [F43](features/F43.md) | Optional Participation | P1 |
| [F44](features/F44.md) | Household Preferences | P3 |
| [F45](features/F45.md) | Expanded Personal Library | P3 |
| [F46](features/F46.md) | Reviewed Situation Packs | P3 |
| [F47](features/F47.md) | Signup Login Recovery | P1 |
| [F48](features/F48.md) | Profile Onboarding | P1 |
| [F49](features/F49.md) | Account Data Privacy | P1 |
| [F50](features/F50.md) | Billing Entitlements | P1 |
| [F51](features/F51.md) | Content Administration | P1 |
| [F52](features/F52.md) | Moderation Operations | P1 |
| [F53](features/F53.md) | Navigation Offline Deep Links | P1 |
| [F54](features/F54.md) | Telemetry Reliability | P1 |

## Rebuild

Run Node.js from this directory:

```sh
node scripts/build.mjs
node scripts/validate.mjs
node scripts/build.mjs
```

The last build embeds the latest verification receipt in the explorer. Generated screen docs, maps, registry files and index.html must be regenerated from the canonical sources. The HTML is self-contained and needs no network/API to explore. Native-device behavior, real backend authorization, load tests, store purchases, professional content review and provider configuration remain explicit production implementation gates.

## Brand rename

The active app name is **FeedMe**. This is a name-only update: the concept, layouts, 54-feature blueprint, 98 screens and 900 canonical action bindings remain intact. The `biteclub_blueprint` and `biteclub_ui` directory names are legacy paths retained so existing links and preview servers continue to work. Earlier BiteClub ZIP archives remain unchanged historical snapshots. The UI asset manifest preserves original image-generation prompts and provenance verbatim.

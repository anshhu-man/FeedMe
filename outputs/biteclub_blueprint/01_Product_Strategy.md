# FeedMe — Product strategy and scope

Status: proposed production blueprint, 13 September 2026. Android and iOS share Kotlin domain code and Compose Multiplatform UI, with native integrations where required. No application, backend, purchase offer or production environment has been deployed by this work. Smart Kitchen's documented dietary matching, practical recommendations and ingredient-reuse ideas inform the product; its implementation has not been inspected for reuse.

## Product decision

**User-confirmed identity, 13 September 2026:** Keep FeedMe intact as the Gen Z smart-cooking and social product developed in this conversation. Do not merge it with, or reposition it around, TasteEcho's earlier "one practical addition to an existing meal" concept. Shipaton research informs delivery constraints and submission preparation, not a product pivot or audience change. The other Devpost "Bite Club" entry is not the user's project. The user separately authorized the name FeedMe; this rename leaves the established concept and feature inventory intact.

FeedMe helps someone turn an ordinary food idea into a meal they can manage with today's ingredients, time, equipment and energy. Friends provide inspiration through “What did you make today?”; Make Mine converts a supported shared recipe into a practical personal version. The experience should remain useful for someone who has no friends in the app and never posts.

The core promise is **healthy cooking should feel less burdensome**. Reviewed everyday recipes and additions support that aspiration. The app's distinctive work is reducing deciding, shopping surprises, preparation, cleanup and repeated setup. Health outcomes are not established by this concept, and public food/body scores are outside the product design.

The brand invitation is **“See a plate. Make it yours.”** Casual real meals, a little humor and small friend circles fit the name. Product copy should make actions clear: a person should know whether they are viewing, saving, cooking, replying or publishing. Expressive branding cannot obscure who can see a post or what persists after 24 hours.

The established identity combines effortless healthy cooking, Make Mine, casual Today sharing and lasting My Plate posts, kitchen circles, and expressive Gen Z design. Optional participation means a person can cook privately; it does not make the product's social identity disposable. The existing 54-feature inventory and 98-screen design remain the full vision. Any contest-specific deferral is a separate proposal requiring approval, not permission to replace FeedMe with TasteEcho or a cooking-only app. Personalization or additions already specified within FeedMe remain supporting capabilities, not a newly imported product thesis.

## Initial user and recurring job

Begin with invited adult pilot participants who prepare everyday meals in ordinary kitchens and often have limited time or energy. Recruitment should include varied equipment, familiar food preferences and Android/iOS devices. Launch country, age/eligibility policy and production data region are unresolved deployment decisions, not assumptions of universal legal readiness.

The recurring job is: “Help me decide what I can make now, then make it easy to repeat or share if I want.” A useful session can end after one recipe, an assembled meal or one addition to existing food. Social participation should add a useful idea or connection without becoming a prerequisite to eat.

## Two connected loops

| Loop | Entry | Helpful outcome | Optional continuation |
|---|---|---|---|
| Personal meal | Ingredients + time + energy | One feasible reviewed meal/addition with literal preparation and cleanup | Make again; scoped feedback; private memory |
| Friend inspiration | A permitted casual food post | Make Mine produces a supported version for the viewer | Your Take credits the inspiration; a friend sees another achievable idea |

Both loops use the same constraints and reviewed content. Make it easier, Something else and Keep the Vibe are ways of correcting a plan. They cannot override exclusions or manufacture unsupported recipes. The story photograph supplies inspiration; verified recipe context supplies actionable instructions.

## Product rules that shape implementation

1. **Useful help comes early.** Guest cooking and manual input remain available without an account, social graph, payment or notification permission. Language interpretation can assist with input but has a deterministic fallback.
2. **The effort promise is observable.** Every automated result exposes ingredients, active and total time, equipment and cleanup. Missing essentials appear before cooking. A simplification must reduce a recorded burden.
3. **Personalization is explainable and editable.** Explicit feedback creates scoped soft preferences. Unavailable ingredients, skipped recipes and missed days do not become dislikes. Forgetting a memory must stop its effect and survive projection rebuilds.
4. **Sharing has explicit boundaries.** One composer shows audience, Keep on my Plate and recipe-copy permission. Today expires using server time. Private copied recipes exclude original media/replies and follow disclosed grant/recall rules.
5. **Money buys added value.** The paid hypotheses are organization tools, explicit household planning and reviewed situation packs. Existing basic saved-recipe access is preserved. Purchase confirmations are reconciled server-side.
6. **Operations are part of the product.** Moderation, reporting, account deletion, content review, recall, permission revocation and offline recovery must work when social features launch, not as a later cleanup project.

## Complete feature scope and phases

The canonical inventory is [spec/model.mjs](spec/model.mjs); each ID has its own implementation specification in features/Fxx.md. P0 is enabling work inside these capabilities, not a second set of feature IDs.

| Phase | Exact feature coverage | Scope decision |
|---|---|---|
| P0 foundations | Foundational slices of F13, F47–F54 plus core architecture | Versioned catalog, identity/guest boundaries, contracts, storage, media/security seams, billing sandbox and operational tooling before feature rollout |
| P1 core cooking | F01–F20 | Meal helper, Make Mine, constraints, sensory additions, reviewed variations, effort, cooking, memory and cookbook; reuse is optional after the immediate meal |
| P1 invited social | F21–F31, F39–F43 | Today/My Plate, circles, attachments, attribution/trail, reactions/replies, recipe requests/saving, composer, privacy, notifications and participation controls |
| P1 production completion | F47–F54 | Full identity/onboarding, data privacy, billing foundations, editorial/moderation operations, native navigation/offline handling and reliability |
| P2 practical entry points | F32–F33 | Fridge SOS and Tonight feed the established meal flow |
| P3 coordination and media | F34–F38 | Dinner Pact, Bring a Bit, Shortcut Swap, Dinner Vote and Short Clips, independently gated |
| P3 optional paid value | F44–F46, activating F50 offers | Household preferences, expanded personal library and reviewed situation packs |

The inventory contains 54 unique features: 44 assigned to P1, two to P2 and eight to P3. P0 implements prerequisites of the P1 feature set. P1 may ship through several pilot slices, but public social access must not precede its permission and moderation controls. P3 features are fully specified so they can be estimated and built later; specification does not authorize immediate live sales or deployment.

## Deliberately outside this blueprint

Photo-based pantry inventory, exact stock quantities, automatic expiry/freshness inference, comprehensive weekly planning, grocery delivery, nutrition dashboards, public discovery and creator marketplaces remain separate product decisions. The product excludes obligatory posting, automatic publication, public body/food scoring, follower competition and streak penalties. Household owner transfer is also deferred until an explicit consent workflow is designed; dissolution and member leaving still need complete lifecycle support.

## Validation and decision evidence

Begin with a proposed formative study of a few existing friend circles across several real evenings; this is a product study, not evidence of clinical benefit. A useful initial target is an acceptable meal within a minute, to be tested rather than marketed as measured performance. Observe the work the person actually does, including ingredient surprises, preparation and cleanup, rather than simply counting time in the app.

| Question | Evidence to collect with consent | Decision it informs |
|---|---|---|
| Does the helper reduce deciding? | Time to an accepted recipe, rejection reason, comparison with the participant's usual process | Simplify input or improve catalog coverage before adding features |
| Is the meal manageable? | Optional report that it was made, actual prep/cleanup feedback, missing essentials | Correct effort metadata and reviewed variants |
| Does memory help later? | Returning use with/without a relevant explicit memory, corrections and forget actions | Keep only understandable useful signals |
| Does social lead to cooking? | Story → Make Mine → accepted plan → separately reported meal, plus optional source-cook feedback | Improve the handoff, not feed depth |
| Are permissions understood? | Participants explain Today versus My Plate versus private recipe copy in their own words | Fix sharing disclosures before wider access |
| Is paid value distinct? | Observed recurring organization/household/pack need and voluntary offering feedback | Decide whether and how to enable an offer |

Clicks, saves and replies are separate events from reported cooking. Attribution across the social-to-cooking loop should use minimal scoped IDs, avoid raw food preferences in analytics, and never expose a user's private activity to the source creator by default. Retention or increased viewing alone is insufficient evidence that dinner became easier.

## Delivery and investment decisions

Use the [implementation roadmap](08_Implementation_Roadmap.md) for cross-feature sequencing, team assumptions and estimated effort; feature specifications supply the detailed tasks and acceptance cases. The highest-risk dependencies are catalog review, consistent exclusion/substitution rules, source-copy rights, native interruption recovery and prompt privacy revocation. Solve those in integrated slices before expanding the catalog, audience or paid offerings.

Before any production launch, record the launch market/eligibility decision, reviewed content capacity, moderation staffing, provider/version compatibility, operational budgets and app-store requirements. Those are explicit delivery decisions to complete during implementation. This blueprint makes their dependencies reviewable; it does not claim they have already been satisfied.

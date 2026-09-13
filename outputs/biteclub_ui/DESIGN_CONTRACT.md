# FeedMe UI — implementation contract

Deliver a new, standalone, responsive high-fidelity HTML/CSS/JS prototype for all 98 canonical screens. Preserve the prior production blueprint. This is a visual front-end with synthetic data and simulated commands, not deployed Kotlin clients or a backend.

## Visual direction

Your kitchen. Your rules. Electric blue #304FFE, acid lime #D4FF5A, coral #FF7657, lilac #D7C9FF, ink #161917, paper #F7F7F2. Large tightly tracked grotesque/condensed headlines; approachable UI text. Real-food editorial photography, asymmetric stickers, restrained outlines, generous white space. No health scores, streak penalties, fake pricing, auto-publication, or implied public discovery. Every domain gets its own composition within a consistent component language.

## Source and ownership

Source registry: ../biteclub_blueprint/registry/screen_registry.json (98 screens, 900 actions). Root owns src/core.js, src/app.js, src/ui.css, build/test scripts, access/settings/membership/operations renderers. Kitchen agent owns src/views-kitchen.js and optional src/kitchen.css. Social agent owns src/views-social.js and optional src/social.css. Image worker owns assets and assets/ASSET_MANIFEST.md only.

Renderer files are plain JavaScript, concatenated at build time (no imports). Register each screen explicitly with `BiteViews.SCREEN_ID = (s, C, state) => html;`. Return interior content only: the root supplies status bar, app bar, scrolling surface, bottom navigation and overflow actions. Never mutate the canonical registry. Every renderer must be explicitly listed in its file; avoid a catch-all generic form renderer as a substitute for unique design.

## Shared renderer helpers

- C.esc(text): HTML escape.
- C.icon(name): 24px line icon; names arrow, plus, close, check, heart, bookmark, chat, camera, clock, spark, search, sliders, bell, settings, people, lock, bolt, leaf, play, repeat, trash, chevron, home, book, user, more, send, shield, logout, upload, link.
- C.action(s, exactCanonicalLabel, {text,kind='secondary',icon,className}={}): button with bound canonical action ID. Kinds primary, secondary, ghost, danger, row, icon. Marks the action rendered. Plain text only in text option.
- C.link(s, exactCanonicalLabel, innerHTML, className=''): rich content button bound to canonical action; marks action rendered.
- C.field(s, fieldId, options={}): accessible real field bound to canonical draft ID; options label, placeholder, appearance ('chips'|'standard'|'toggle'). Uses canonical options. Marks field rendered.
- C.fields(s, ids?): concatenates C.field. All unrendered fields remain available in an overflow form panel, never silently dropped.
- C.photo(name, className='', alt=''): name wrap, bowl, noodles → assets/{name}.png, using object-fit cover.
- C.avatar(initials,tone='lilac'): styled monogram avatar.
- C.chip(text,tone='neutral'): non-interactive metadata pill.
- C.hero({eyebrow,title,copy,tone='paper'}): heading block; title may contain trusted <br> markup.
- C.section(title,inner): section with heading.

Shared CSS: .stack, .row, .between, .muted, .eyebrow, .small, .title-xl, .title-lg, .body-copy, .surface, .surface-blue, .surface-lime, .surface-lilac, .surface-coral, .food-card, .food-image, .food-copy, .chips, .divider, .avatar-stack, .list-row, .progress-track, .sticker, .two-col, .metric, .quiet-note. You can add domain-prefixed CSS in your own file.

All images are generated illustration fixtures, all names and conversations synthetic. Copy must be casual and delightful without obscuring consent, privacy, destructive actions, pending states or recipe provenance. A set of fixture food cards is not a reviewed live catalog. No external network dependencies. No emoji used as replacement icons. All action targets and original contracts remain inspectable in the shell.

/* FeedMe kitchen and personal UI. All names, photos and meal content are demo fixtures. */
const KitchenUI = {
  badge(C, text, tone = 'lime') { return `<span class="k-badge k-${tone}">${C.esc(text)}</span>`; },
  head(C, eyebrow, title, copy = '') { return `<header class="k-heading"><p class="eyebrow">${C.esc(eyebrow)}</p><h1>${title}</h1>${copy ? `<p class="body-copy">${C.esc(copy)}</p>` : ''}</header>`; },
  food(s, C, action, name, title, meta, tag = '', tone = '') { return C.link(s, action, `<div class="k-card-image">${C.photo(name, 'k-cover', title)}${tag ? this.badge(C, tag) : ''}<span class="k-card-arrow">${C.icon('arrow')}</span></div><div class="k-card-copy"><h3>${C.esc(title)}</h3><p>${C.esc(meta)}</p></div>`, `k-food ${tone}`); },
  note(C, icon, title, text, tone = '') { return `<aside class="k-note ${tone}"><span>${C.icon(icon)}</span><div><strong>${C.esc(title)}</strong><p>${C.esc(text)}</p></div></aside>`; },
  mini(s, C, action, name, title, meta) { return C.link(s, action, `${C.photo(name, 'k-mini-photo', title)}<span class="k-mini-copy"><strong>${C.esc(title)}</strong><small>${C.esc(meta)}</small></span>${C.icon('chevron')}`, 'k-mini'); },
  row(s, C, action, icon, title, copy, tone = '') { return C.link(s, action, `<span class="k-row-icon ${tone}">${C.icon(icon)}</span><span class="k-row-copy"><strong>${C.esc(title)}</strong><small>${C.esc(copy)}</small></span>${C.icon('chevron')}`, 'k-choice-row'); },
  ingredient(C, name, detail, state = 'yes') { return `<div class="k-ingredient"><span class="k-check k-check-${state}">${C.icon(state === 'yes' ? 'check' : state === 'unknown' ? 'clock' : 'plus')}</span><div><strong>${C.esc(name)}</strong><small>${C.esc(detail)}</small></div><span class="k-ingredient-label">${state === 'yes' ? 'Confirmed' : state === 'unknown' ? 'Check first' : 'Missing'}</span></div>`; },
  footer(C, text = 'Illustrative meal · not a live reviewed recipe') { return `<p class="k-fixture-note">${C.esc(text)}</p>`; }
};

BiteViews.FOOD_PREFS = (s, C, state) => `<div class="k-screen">
  <div class="k-setup-progress"><span class="is-filled"></span><span class="is-filled"></span><span></span><small>YOUR BASICS · 2 OF 3</small></div>
  ${KitchenUI.head(C, 'Your food, your call', 'A little<br>more <em>you.</em>', 'Tell us what works for you. Change it whenever life does.')}
  <div class="k-pref-illustration"><span class="k-pref-orbit k-orbit-one">${C.icon('leaf')}</span><span class="k-pref-orbit k-orbit-two">${C.icon('spark')}</span><span class="k-pref-word">NO FOOD<br>RULEBOOK.</span></div>
  <div class="k-form-card">${C.field(s, 'diet', { appearance: 'chips' })}<div class="k-form-divider"></div>${C.field(s, 'exclude', { placeholder: 'Anything we should leave out?' })}${C.field(s, 'dislike', { placeholder: 'Coriander? Mushrooms? You decide.' })}</div>
  ${KitchenUI.note(C, 'shield', 'A preference, not a safety guarantee', 'We use your exclusions to filter suggestions. Always check labels, ingredients and preparation yourself.')}
  ${C.action(s, 'Save preferences', { text: 'That’s my taste', kind: 'primary', icon: 'arrow' })}${C.action(s, 'Skip for now', { kind: 'ghost' })}
</div>`;

BiteViews.EQUIPMENT = (s, C, state) => `<div class="k-screen">
  <div class="k-setup-progress"><span class="is-filled"></span><span class="is-filled"></span><span class="is-filled"></span><small>YOUR BASICS · 3 OF 3</small></div>
  ${KitchenUI.head(C, 'Big kitchen energy. Any kitchen.', 'Work with<br>what <em>you’ve got.</em>', 'Tiny kitchen? One pan? Microwave era? You’re in the club.')}
  <div class="k-equipment-poster"><span class="k-equipment-mark">${C.icon('bolt')}</span><p>ONE BOWL.<br>STILL A MEAL.</p><span class="k-poster-label">NO FANCY SETUP REQUIRED</span></div>
  <div class="k-form-card">${C.field(s, 'equipment', { appearance: 'chips' })}${C.field(s, 'servings', { label: 'How many are we feeding?' })}</div>
  ${KitchenUI.note(C, 'people', 'Just the usual', 'These are your defaults. You can change the serving count and equipment for any meal.')}
  ${C.action(s, 'Save kitchen setup', { text: 'Let’s get cooking', kind: 'primary', icon: 'arrow' })}${C.action(s, 'Finish without defaults', { text: 'I’ll decide meal by meal', kind: 'ghost' })}
</div>`;

BiteViews.HOME = (s, C, state) => `<div class="k-screen">
  <div class="k-welcome-line"><span>YOUR KITCHEN. YOUR RULES.</span>${C.chip('Dinner mode', 'lime')}</div>
  ${KitchenUI.head(C, 'Hey, you.', 'Good food.<br><em>Less effort.</em>')}
  <div class="k-home-hero">
    ${C.photo('wrap', 'k-home-photo', 'A colorful crisp vegetable wrap on a plate')}
    <span class="k-sticker">LOW EFFORT<br>HIGH REWARD</span>
    <div class="k-home-hero-copy"><p class="eyebrow">WHAT’S THE MOVE?</p><h2>Dinner doesn’t<br>need a plot twist.</h2>${C.action(s, 'Help me make something', { text: 'Make me something', kind: 'primary', icon: 'spark' })}</div>
  </div>
  <div class="k-shortcuts">
    ${C.link(s, 'Use what I have', `<span class="k-shortcut-icon">${C.icon('leaf')}</span><strong>Fridge<br>freestyle</strong><small>Use what I have</small>${C.icon('arrow')}`, 'k-shortcut k-lime')}
    ${C.link(s, 'Improve my meal', `<span class="k-shortcut-icon">${C.icon('bolt')}</span><strong>Give it<br>a glow-up</strong><small>Improve my meal</small>${C.icon('arrow')}`, 'k-shortcut k-lilac')}
  </div>
  <div class="k-section-heading"><h2>Your reliable ones</h2>${C.action(s, 'My reliable meals', { text: 'All saves', kind: 'ghost', icon: 'arrow' })}</div>
  ${KitchenUI.mini(s, C, 'My reliable meals', 'bowl', 'The no-drama bowl', 'A saved-meal preview · private to you')}
  ${KitchenUI.row(s, C, 'See today’s plates', 'people', 'What’s cooking in your circle?', 'The real-life plate check. No perfect kitchens.', 'k-coral')}
  ${KitchenUI.footer(C, 'Demo kitchen · food and saved meals are illustrative')}
</div>`;

BiteViews.REQUEST = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'Smart meal helper', 'What’s your<br><em>dinner situation?</em>', 'The ingredients. The mood. The “I cannot be bothered.” We get it.')}
  <div class="k-request-card"><span class="k-request-spark">${C.icon('spark')}</span>${C.field(s, 'request', { label: 'Talk to your kitchen', placeholder: 'Rotis, yogurt, cucumber. Ten minutes. Very little energy.' })}<p class="k-input-hint">Plain language is perfect. We’ll check the details with you.</p></div>
  <div class="k-form-card">${C.field(s, 'mode', { appearance: 'chips', label: 'How are we making it happen?' })}${C.field(s, 'baseMeal', { placeholder: 'Only if you’re improving something ready' })}</div>
  <div class="k-settings-rows">${KitchenUI.row(s, C, 'Check ingredients', 'leaf', 'What’s in the kitchen?', 'Confirm what you actually have', 'k-lime')}${KitchenUI.row(s, C, 'Set time and effort', 'clock', 'Match my energy', 'Time, prep and cleanup', 'k-lilac')}${KitchenUI.row(s, C, 'Choose a taste', 'spark', 'Pick the vibe', 'Crunchy, creamy, fresh or spicy', 'k-coral')}</div>
  ${C.action(s, 'Find a meal', { text: 'Find my dinner', kind: 'primary', icon: 'arrow' })}
  <p class="k-fixture-note">Suggestions show their ingredients and limits before you commit.</p>
</div>`;

BiteViews.PANTRY = (s, C, state) => `<div class="k-screen">
  <div class="k-title-sticker">${KitchenUI.head(C, 'Use what I have', 'Fridge<br><em>freestyle.</em>', 'A few ingredients. So many possibilities. No inventory spreadsheet required.')}<span class="k-small-sticker">START<br>SMALL</span></div>
  <div class="k-form-card">${C.field(s, 'ingredient', { placeholder: 'Type an ingredient…' })}${C.field(s, 'availability', { appearance: 'chips' })}${C.action(s, 'Add or update ingredient', { text: 'Add to my kitchen', kind: 'primary', icon: 'plus' })}</div>
  <div class="k-section-heading"><h2>Your quick check</h2><span class="k-list-count">DEMO LIST</span></div>
  <div class="k-inventory">${KitchenUI.ingredient(C, 'Cucumber', 'One ingredient is a start.')}${KitchenUI.ingredient(C, 'Yogurt', 'Confirm it is available today.')}${KitchenUI.ingredient(C, 'Rotis', 'A usual staple is not a promise.', 'unknown')}</div>
  ${C.action(s, 'Remove selected ingredient', { text: 'Remove selected ingredient', kind: 'ghost', icon: 'trash' })}
  ${KitchenUI.note(C, 'clock', '“Usually have” means check first', 'Availability is not freshness. We won’t infer whether an ingredient is safe to eat.')}
  ${C.action(s, 'Use these ingredients', { kind: 'primary', icon: 'arrow' })}${C.action(s, 'Time and effort', { text: 'Set my energy too', kind: 'secondary', icon: 'bolt' })}
</div>`;

BiteViews.EFFORT = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'The energy check', 'Big appetite.<br><em>Low battery?</em>', 'Dinner should fit your day. Not the other way around.')}
  <div class="k-energy-poster"><div class="k-battery"><i></i><i></i><i></i><span></span></div><p>ALL ENERGY<br>LEVELS WELCOME.</p></div>
  <div class="k-form-card">${C.field(s, 'effort', { appearance: 'chips', label: 'What feels doable?' })}<div class="k-time-fields">${C.field(s, 'minutes', { label: 'Total minutes' })}${C.field(s, 'activeMinutes', { label: 'Hands-on minutes' })}</div>${C.field(s, 'cleanup', { appearance: 'chips', label: 'And the cleanup?' })}</div>
  ${KitchenUI.row(s, C, 'Change equipment', 'sliders', 'Kitchen setup', 'Use the equipment you have', 'k-lilac')}
  ${C.action(s, 'Apply to this meal', { text: 'That’s my energy', kind: 'primary', icon: 'check' })}${C.action(s, 'Find my meal', { text: 'Back to my request', kind: 'ghost' })}
  <p class="k-fixture-note">Just for this meal. Your everyday defaults stay the same.</p>
</div>`;

BiteViews.TASTE = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'Taste check', 'What’s<br>the <em>vibe?</em>', 'A craving is a perfectly good place to start.')}
  <div class="k-taste-collage"><div class="k-taste-photo">${C.photo('bowl', 'k-cover', 'Fresh and colorful bowl inspiration')}</div><span class="k-taste-type k-taste-a">CRUNCH.</span><span class="k-taste-type k-taste-b">FRESH.</span><span class="k-taste-type k-taste-c">YOUR CALL.</span></div>
  <div class="k-form-card">${C.field(s, 'taste', { appearance: 'chips', label: 'I’m in the mood for…' })}</div>
  ${KitchenUI.note(C, 'shield', 'Flavor comes after your boundaries', 'Your ingredient exclusions, equipment and effort limits still apply. Taste is optional.')}
  ${C.action(s, 'Apply choice', { text: 'Keep this vibe', kind: 'primary', icon: 'spark' })}${C.action(s, 'Back to meal request', { text: 'Anything works today', kind: 'ghost' })}
</div>`;

BiteViews.RECOMMENDATIONS = (s, C, state) => `<div class="k-screen">
  <div class="k-result-kicker">${C.icon('spark')}<span>A LITTLE DINNER INSPIRATION</span></div>
  ${KitchenUI.head(C, 'Your meal match', 'Looks like<br><em>your kind of easy.</em>')}
  ${KitchenUI.food(s, C, 'View recipe', 'wrap', 'Crunch-time wrap', 'Cool yogurt · crisp cucumber · warm roti', 'THE EASY ONE')}
  <div class="k-metrics"><div><span>${C.icon('clock')}</span><strong>10 min</strong><small>total time</small></div><div><span>${C.icon('bolt')}</span><strong>5 min</strong><small>hands-on</small></div><div><span>${C.icon('leaf')}</span><strong>1 bowl</strong><small>cleanup</small></div></div>
  <div class="k-match-note"><span>${C.icon('check')}</span><p>A light-prep assembly idea. Confirm your ingredients before you start.</p>${C.action(s, 'Why this meal?', { text: 'Why this?', kind: 'ghost' })}</div>
  ${C.action(s, 'Make it mine', { text: 'Make Mine', kind: 'primary', icon: 'spark' })}
  <div class="k-two-buttons">${C.action(s, 'Make it easier', { text: 'Even easier', kind: 'secondary', icon: 'bolt' })}${C.action(s, 'Something else', { kind: 'secondary', icon: 'repeat' })}</div>
  ${KitchenUI.footer(C, 'Illustrative match · timings and ingredients are demo data')}
</div>`;

BiteViews.RECIPE = (s, C, state) => `<div class="k-screen">
  <div class="k-recipe-photo">${C.photo('wrap', 'k-cover', 'A crisp cucumber and yogurt wrap')}${KitchenUI.badge(C, 'YOUR NEXT GOOD MEAL')}<span class="k-recipe-save">${C.action(s, 'Save recipe', { text: 'Save recipe', kind: 'icon', icon: 'bookmark' })}</span></div>
  <div class="k-recipe-heading"><p class="eyebrow">FEEDME DEMO KITCHEN</p><h1>Crunch-time<br>wrap.</h1><p>Fresh, creamy, a little crunchy. A whole mood in one wrap.</p></div>
  <div class="k-recipe-meta"><span>${C.icon('clock')}10 min total</span><span>${C.icon('bolt')}5 min prep</span><span>${C.icon('user')}1 serving</span></div>
  ${C.action(s, 'Start cooking', { kind: 'primary', icon: 'play' })}${C.action(s, 'Make Mine', { text: 'Make Mine · tweak it for me', kind: 'secondary', icon: 'spark' })}
  <div class="k-section-heading"><h2>The lineup</h2><span class="k-list-count">4 INGREDIENTS</span></div>
  <div class="k-recipe-ingredients"><div><span>01</span><strong>Rotis</strong><small>2 small</small></div><div><span>02</span><strong>Cucumber</strong><small>½, sliced</small></div><div><span>03</span><strong>Plain yogurt</strong><small>3 tbsp</small></div><div><span>04</span><strong>Lemon juice</strong><small>1 tsp, optional</small></div></div>
  ${C.action(s, 'An ingredient is unavailable', { text: 'Missing something? Let’s swap.', kind: 'ghost', icon: 'repeat' })}
  <div class="k-recipe-steps"><h2>Three small moves</h2><ol><li><b>Slice.</b> Wash and thinly slice the cucumber.</li><li><b>Mix.</b> Stir the yogurt and optional lemon juice in a bowl.</li><li><b>Build.</b> Spread, layer, roll. That’s your meal.</li></ol></div>
  ${KitchenUI.note(C, 'book', 'Know the source', 'Illustrative FeedMe recipe layout. Production shows its licensed source, review status and pinned revision here.')}
  ${C.action(s, 'Share this meal', { text: 'Share my take', kind: 'secondary', icon: 'camera' })}
</div>`;

BiteViews.ADAPT = (s, C, state) => `<div class="k-screen k-adapt-screen">
  <div class="k-make-mine-brand"><span>MAKE</span><span>MINE<span class="k-star-dot">✳</span></span></div>
  <p class="k-make-mine-intro">Same inspiration.<br><strong>Your ingredients. Your energy.</strong></p>
  ${KitchenUI.mini(s, C, 'Keep original', 'wrap', 'Starting with Crunch-time wrap', 'Keep the original whenever you want')}
  <div class="k-form-card k-adapt-form">${C.field(s, 'change', { label: 'What would make this yours?', placeholder: 'Use what I have. Keep the crunch. Less prep, please.' })}${C.field(s, 'minutes', { label: 'Time you actually have' })}</div>
  ${C.action(s, 'Make my version', { text: 'Make my version', kind: 'primary', icon: 'spark' })}
  <div class="k-adapt-tools">${KitchenUI.row(s, C, 'Edit ingredients', 'leaf', 'My ingredients', 'Swap with what’s available', 'k-lime')}${KitchenUI.row(s, C, 'Change effort', 'bolt', 'My energy', 'Prep and cleanup that fit', 'k-lilac')}${KitchenUI.row(s, C, 'Change taste', 'spark', 'My vibe', 'Keep the part you love', 'k-coral')}</div>
  ${KitchenUI.note(C, 'lock', 'Your exclusions stay fixed', 'We show the changes and tradeoffs before you choose a version.')}
</div>`;

BiteViews.VARIANT = (s, C, state) => `<div class="k-screen">
  <div class="k-variant-top">${C.chip('MAKE MINE', 'blue')}<span>YOUR VERSION, EXPLAINED</span></div>
  ${KitchenUI.head(C, 'A small switch. Still your vibe.', 'Same crunch.<br><em>Your remix.</em>')}
  <div class="k-variant-photo">${C.photo('wrap', 'k-cover', 'An illustrative wrap adaptation')}<span class="k-round-sticker">KEEP<br>THE VIBE</span></div>
  <div class="k-comparison"><div class="k-compare-labels"><span>THE ORIGINAL</span>${C.icon('arrow')}<span>YOUR VERSION</span></div><div class="k-compare-row"><span>Extra garnish</span>${C.icon('arrow')}<strong>Keep it simple</strong></div><div class="k-compare-row"><span>Separate plating</span>${C.icon('arrow')}<strong>One bowl + wrap</strong></div><div class="k-compare-foot">${C.icon('check')}Core texture stays crisp and creamy</div></div>
  <div class="k-metrics k-compact-metrics"><div><strong>10 min</strong><small>total</small></div><div><strong>5 min</strong><small>hands-on</small></div><div><strong>Less prep</strong><small>same meal idea</small></div></div>
  ${KitchenUI.note(C, 'book', 'Nothing changed behind your back', 'This is an illustrative comparison. Live adaptations must come from reviewed options and show real changes, reasons and uncertainty.')}
  ${C.action(s, 'Use this version', { text: 'This one’s mine', kind: 'primary', icon: 'check' })}<div class="k-two-buttons">${C.action(s, 'Try another swap', { text: 'Another swap', kind: 'secondary', icon: 'repeat' })}${C.action(s, 'View original', { kind: 'ghost' })}</div>
</div>`;

BiteViews.COOK = (s, C, state) => `<div class="k-screen k-cook-screen">
  <div class="k-cook-context"><span>CRUNCH-TIME WRAP</span>${C.chip('Cooking mode', 'lime')}</div>
  <div class="k-step-progress"><i class="is-active"></i><i></i><i></i></div>
  <div class="k-step-heading"><span>STEP 01 / 03</span><h1>Slice<br>the <em>crunch.</em></h1></div>
  <p class="k-step-instruction">Wash the cucumber. Slice it into thin, bite-size pieces.</p>
  <div class="k-step-visual">${C.photo('bowl', 'k-cover', 'Illustrative fresh cucumber in a colorful bowl')}<div class="k-step-quantity"><span>YOU NEED</span><strong>½ cucumber</strong><small>A knife + chopping board</small></div></div>
  ${KitchenUI.note(C, 'leaf', 'Make yourself comfortable', 'Take your time. You can check the ingredients without losing your place.', 'k-lime')}
  ${C.action(s, 'Next step', { text: 'Next little move', kind: 'primary', icon: 'arrow' })}
  <div class="k-two-buttons">${C.action(s, 'See ingredients', { text: 'Ingredients', kind: 'secondary', icon: 'book' })}${C.action(s, 'Start step timer', { text: 'Set a timer', kind: 'secondary', icon: 'clock' })}</div>
  <div class="k-cook-bottom">${C.action(s, 'Previous step', { text: 'Previous', kind: 'ghost' })}${C.action(s, 'Pause and leave', { text: 'Pause for now', kind: 'ghost' })}</div>
  ${C.action(s, 'Finish meal', { text: 'My meal is ready', kind: 'ghost', icon: 'check' })}
  ${KitchenUI.footer(C, 'Demonstration step · production pins the reviewed recipe revision')}
</div>`;

BiteViews.TIMER = (s, C, state) => `<div class="k-screen k-timer-screen">
  ${KitchenUI.head(C, 'A little kitchen pause', 'Take your<br><em>two minutes.</em>')}
  <div class="k-timer-dial"><span class="k-timer-tick k-tick-top"></span><span class="k-timer-tick k-tick-right"></span><span class="k-timer-tick k-tick-bottom"></span><span class="k-timer-tick k-tick-left"></span><div><p>STEP TIMER</p><strong>02<span>:</span>00</strong><small>Ready when you are</small></div></div>
  <div class="k-form-card">${C.field(s, 'seconds', { label: 'Duration in seconds' })}${C.field(s, 'timerId', { appearance: 'chips', label: 'Choose your timer' })}</div>
  ${C.action(s, 'Start timer', { kind: 'primary', icon: 'play' })}<div class="k-two-buttons">${C.action(s, 'Pause timer', { text: 'Pause', kind: 'secondary', icon: 'clock' })}${C.action(s, 'Reset timer', { text: 'Reset', kind: 'secondary', icon: 'repeat' })}</div>
  ${C.action(s, 'Back to cooking', { kind: 'ghost', icon: 'arrow' })}
  <p class="k-fixture-note">Timer controls are simulated in this prototype. In the app, alerts depend on your device permissions.</p>
</div>`;

BiteViews.MEAL_DONE = (s, C, state) => `<div class="k-screen">
  <div class="k-done-poster"><span class="k-done-spark k-done-spark-one">${C.icon('spark')}</span><p>OFFICIALLY</p><h1>YOU<br><em>MADE IT.</em></h1><span class="k-done-spark k-done-spark-two">${C.icon('spark')}</span><div class="k-done-photo">${C.photo('wrap', 'k-cover', 'Your illustrative finished wrap')}<span class="k-done-check">${C.icon('check')}</span></div></div>
  <div class="k-done-copy"><h2>That counts as a win.</h2><p>No perfect plating required. Enjoy your food.</p></div>
  ${C.action(s, 'Make again', { text: 'I’d make this again', kind: 'primary', icon: 'bookmark' })}
  <div class="k-two-buttons">${C.action(s, 'Share Your Take', { text: 'Share my take', kind: 'secondary', icon: 'camera' })}${C.action(s, 'Leave a quick thought', { text: 'Quick thought', kind: 'secondary', icon: 'chat' })}</div>
  ${KitchenUI.row(s, C, 'Use an ingredient again', 'repeat', 'Got a little left?', 'Find a next use for one ingredient', 'k-lime')}
  ${C.action(s, 'Done for now', { text: 'I’m off to eat', kind: 'ghost', icon: 'arrow' })}
  <p class="k-fixture-note">Saving, feedback and posting are always separate. Nothing is shared automatically.</p>
</div>`;

BiteViews.FEEDBACK = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'Tiny thought. Better next time.', 'So… how<br>did it <em>hit?</em>', 'No ratings homework. Tell us only what you want us to remember.')}
  <div class="k-feedback-cover">${C.photo('wrap', 'k-feedback-photo', 'The illustrative meal you just made')}<div><span class="eyebrow">YOUR JUST-MADE MEAL</span><h3>Crunch-time wrap</h3><span class="k-inline-lock">${C.icon('lock')}Only for your suggestions</span></div></div>
  <div class="k-form-card">${C.field(s, 'reaction', { appearance: 'chips' })}${C.field(s, 'note', { label: 'Anything else? Totally optional.', placeholder: 'Loved the crunch. Could do with less chopping.' })}</div>
  <details class="k-feedback-detail"><summary>Make this feedback more specific ${C.icon('chevron')}</summary><div>${C.field(s, 'scope', { appearance: 'chips' })}${C.field(s, 'target', { placeholder: 'Name an ingredient or texture' })}</div></details>
  ${KitchenUI.note(C, 'spark', 'You’re in charge of what sticks', 'Any taste memory we create can be inspected, changed or forgotten.')}
  ${C.action(s, 'Save feedback', { text: 'Save my thought', kind: 'primary', icon: 'check' })}${C.action(s, 'Skip', { text: 'Just let me eat', kind: 'ghost' })}
</div>`;

BiteViews.REUSE = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'One ingredient. Another moment.', 'The delicious<br><em>encore.</em>', 'Give something you already have another good use.')}
  <div class="k-encore-art"><div class="k-encore-photo">${C.photo('bowl', 'k-cover', 'An illustrative bright vegetable bowl')}</div><span class="k-encore-arrow">${C.icon('repeat')}</span><div class="k-encore-ticket"><small>NEXT UP</small><strong>A fresh<br>little idea.</strong></div></div>
  <div class="k-form-card">${C.field(s, 'ingredient', { placeholder: 'Cucumber, yogurt, cooked rice…' })}${C.field(s, 'effort', { appearance: 'chips' })}</div>
  ${KitchenUI.note(C, 'shield', 'You know your ingredients best', 'We can suggest a next use, but we can’t judge freshness or storage safety. Use only food you know is safe.')}
  ${C.action(s, 'Find a next use', { text: 'Find its next act', kind: 'primary', icon: 'repeat' })}${C.action(s, 'Not now', { kind: 'ghost' })}
</div>`;

BiteViews.COOKBOOK = (s, C, state) => `<div class="k-screen">
  <div class="k-book-header"><div>${KitchenUI.head(C, 'Collected by you', 'The <em>keepers.</em>')}</div><span class="k-book-lock">${C.icon('lock')}PRIVATE</span></div>
  <p class="k-book-intro">For the nights you don’t want to decide.</p>
  <div class="k-book-search">${C.field(s, 'search', { label: 'Find a keeper', placeholder: 'Search your saved meals' })}</div>
  <div class="k-book-tabs"><span class="is-active">All saves</span><span>Make again</span><span>Collections</span></div>
  <div class="k-library-grid">${KitchenUI.food(s, C, 'Open saved meal', 'wrap', 'Crunch-time wrap', 'Your easy one', 'MAKE AGAIN')}${KitchenUI.food(s, C, 'Tonight?', 'bowl', 'No-drama bowl', 'Make it tonight?')}${KitchenUI.food(s, C, 'Open saved meal', 'noodles', 'Noodles, your way', 'Big flavor energy')}${C.link(s, 'Create collection', `<span>${C.icon('plus')}</span><strong>A little<br>collection?</strong><small>Your kind of organized</small>`, 'k-new-collection')}</div>
  ${KitchenUI.row(s, C, 'Open collection', 'book', 'Weeknight favorites', 'Your saved recipes, grouped your way', 'k-lilac')}
  ${KitchenUI.row(s, C, 'Manage taste memory', 'spark', 'Your taste, remembered', 'See what shapes your suggestions', 'k-lime')}
  ${KitchenUI.footer(C, 'Demo saves · private recipe copies do not grant reposting rights')}
</div>`;

BiteViews.COLLECTION = (s, C, state) => `<div class="k-screen">
  <div class="k-collection-hero"><div class="k-collection-cover"><span class="k-collection-number">01</span><p>THE<br>WEEKNIGHT<br><em>EDIT.</em></p><span>${C.icon('book')} A PRIVATE COLLECTION</span></div><div class="k-collection-heading"><h1>Weeknight<br>favorites</h1>${C.action(s, 'Edit collection', { text: 'Edit collection', kind: 'ghost', icon: 'sliders' })}</div></div>
  <p class="k-book-intro">A few good meals. One less decision.</p>
  <div class="k-collection-list">${KitchenUI.mini(s, C, 'Open recipe', 'wrap', 'Crunch-time wrap', '10-minute illustrative meal')}${KitchenUI.mini(s, C, 'Open recipe', 'bowl', 'No-drama bowl', 'A one-bowl kind of evening')}${KitchenUI.mini(s, C, 'Open recipe', 'noodles', 'Noodles, your way', 'For when the craving calls')}</div>
  ${C.action(s, 'Make this tonight', { text: 'Make a keeper tonight', kind: 'primary', icon: 'spark' })}${C.action(s, 'Add existing save', { text: 'Add one of my saves', kind: 'secondary', icon: 'plus' })}
  ${KitchenUI.note(C, 'lock', 'Your collection stays yours', 'This organizes private recipe saves. It doesn’t publish them or change the original recipe.')}
</div>`;

BiteViews.COLLECTION_EDIT = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'A place for your favorites', 'Call it<br><em>your thing.</em>', 'Desk-lunch legends. No-energy dinners. Whatever makes sense to you.')}
  <div class="k-collection-edit-preview"><span class="k-collection-number">FM / 01</span><h2>THE<br>GOOD<br>STUFF.</h2><span class="k-preview-book">${C.icon('book')}</span></div>
  <div class="k-form-card">${C.field(s, 'name', { placeholder: 'Weeknight favorites' })}${C.field(s, 'items', { appearance: 'chips', label: 'Start with a saved meal' })}</div>
  <div class="k-order-preview"><div><span class="k-order-number">01</span><strong>Selected saved meal</strong><span class="k-order-arrows">${C.action(s, 'Move selected recipe up', { text: 'Move up', kind: 'icon', icon: 'arrow', className: 'k-point-up' })}${C.action(s, 'Move selected recipe down', { text: 'Move down', kind: 'icon', icon: 'arrow', className: 'k-point-down' })}</span></div><small>Advanced ordering requires the corresponding library capability.</small></div>
  ${C.action(s, 'Save collection', { text: 'Create my collection', kind: 'primary', icon: 'check' })}${C.action(s, 'Cancel', { text: 'Leave it for now', kind: 'ghost' })}
</div>`;

BiteViews.MEMORY = (s, C, state) => `<div class="k-screen">
  ${KitchenUI.head(C, 'Your taste, in your words', 'We’re taking<br><em>your notes.</em>', 'Not judging your plate. Just remembering the little things you choose to tell us.')}
  <div class="k-memory-banner"><span>${C.icon('spark')}</span><strong>A little more you.<br>A little less guessing.</strong></div>
  <div class="k-section-heading"><h2>What’s sticking</h2>${C.chip('Only you', 'lilac')}</div>
  ${C.link(s, 'Inspect a memory', `<div class="k-memory-icon k-lime">${C.icon('leaf')}</div><span class="k-memory-tag">TASTE NOTE</span><h3>Here for<br>the crunch.</h3><p>From your explicit meal feedback</p><div class="k-memory-card-foot"><span>View, edit or forget</span>${C.icon('arrow')}</div>`, 'k-memory-card')}
  ${KitchenUI.note(C, 'lock', 'No secret food profile', 'Memories come from feedback you gave. Saving a recipe or skipping a meal is not a hidden taste vote.')}
  ${KitchenUI.row(s, C, 'Edit food preferences', 'sliders', 'Your firm preferences', 'Food choices and exclusions', 'k-lilac')}
  ${C.action(s, 'Pause personalization', { text: 'Pause learned suggestions', kind: 'secondary', icon: 'clock' })}${C.action(s, 'Return to saved meals', { kind: 'ghost' })}
  ${KitchenUI.footer(C, 'Illustrative memory · exclusions remain active while personalization is paused')}
</div>`;

BiteViews.MEMORY_DETAIL = (s, C, state) => `<div class="k-screen">
  <div class="k-memory-detail-cover"><span>${C.icon('spark')}</span><p>ONE OF YOUR LITTLE THINGS</p><h1>That<br><em>crunch.</em></h1><div class="k-memory-detail-tags">${C.chip('Texture', 'paper')}${C.chip('From your feedback', 'paper')}</div></div>
  <div class="k-memory-source"><p class="eyebrow">WHY WE REMEMBERED IT</p><blockquote>“Loved the crunch.”</blockquote><span>A synthetic example of explicit feedback</span></div>
  ${KitchenUI.mini(s, C, 'View source meal', 'wrap', 'From Crunch-time wrap', 'Inspect the source meal')}
  <div class="k-form-card">${C.field(s, 'strength', { appearance: 'chips', label: 'How should we use this?' })}</div>
  ${KitchenUI.note(C, 'sliders', 'Taste preference, not an exclusion', 'This can influence which eligible meals appear first. It never overrides your ingredient exclusions.')}
  ${C.action(s, 'Update memory', { text: 'Keep it like this', kind: 'primary', icon: 'check' })}${C.action(s, 'Forget this', { text: 'Forget this note', kind: 'ghost', icon: 'trash' })}
</div>`;

BiteViews.TONIGHT = (s, C, state) => `<div class="k-screen">
  <div class="k-tonight-kicker"><span class="k-tonight-dot"></span>FROM SAVED TO SERVED</div>
  ${KitchenUI.head(C, 'You saved it for a reason.', 'Tonight’s<br><em>the night?</em>', 'Let’s see if this keeper fits the kitchen and energy you have right now.')}
  <div class="k-tonight-card">${C.photo('bowl', 'k-cover', 'Your illustrative saved bowl')}<div><span>ON YOUR SAVED LIST</span><h2>No-drama<br>bowl.</h2></div><span class="k-tonight-bookmark">${C.icon('bookmark')}</span></div>
  <div class="k-form-card">${C.field(s, 'minutes', { label: 'Minutes you have tonight' })}${C.field(s, 'effort', { appearance: 'chips', label: 'Tonight’s energy' })}</div>
  ${KitchenUI.row(s, C, 'Confirm ingredients', 'leaf', 'Quick fridge check', 'Confirm what is actually here tonight', 'k-lime')}
  ${C.action(s, 'Check tonight’s version', { text: 'Let’s make tonight’s version', kind: 'primary', icon: 'spark' })}${C.action(s, 'Back to my saves', { text: 'Not this one tonight', kind: 'ghost' })}
  <p class="k-fixture-note">We check current ingredients and constraints before cooking. A saved recipe is not a promise that everything is available.</p>
</div>`;

/* Systems UI: privacy, membership and desktop operations. All content is a fixture. */
const SYS={
  note:(C,icon,text,cls='')=>'<div class="sys-note '+cls+'">'+C.icon(icon)+'<p>'+C.esc(text)+'</p></div>',
  line:(C,icon,title,copy)=>'<div class="sys-detail">'+C.icon(icon)+'<div><b>'+C.esc(title)+'</b><p>'+C.esc(copy)+'</p></div></div>',
  head:(C,kicker,title,copy)=>C.hero({eyebrow:kicker,title,copy}),
  opsHead:(C,kicker,title,copy)=>'<div class="ops-heading"><div>'+C.hero({eyebrow:kicker,title,copy})+'</div>'+C.chip('Synthetic workspace','lilac')+'</div>',
  label:(C,title,status,tone='neutral')=>'<div class="between sys-label"><span>'+C.esc(title)+'</span>'+C.chip(status,tone)+'</div>',
  stat:(C,label,value,copy,tone='')=>'<div class="ops-metric '+tone+'"><div class="eyebrow">'+C.esc(label)+'</div><div class="metric">'+C.esc(value)+'</div><p class="small muted">'+C.esc(copy)+'</p></div>',
  steps:(C,rows)=>'<ol class="sys-steps">'+rows.map((r,i)=>'<li><span>'+String(i+1).padStart(2,'0')+'</span><div><b>'+C.esc(r[0])+'</b><p>'+C.esc(r[1])+'</p></div></li>').join('')+'</ol>',
  table:(C,heads,rows)=>'<div class="ops-table-wrap"><table class="ops-table"><thead><tr>'+heads.map(h=>'<th scope="col">'+C.esc(h)+'</th>').join('')+'</tr></thead><tbody>'+rows.map(row=>'<tr>'+row.map(cell=>'<td>'+cell+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>',
  identity:(C,initials,name,sub,tone='lilac')=>'<div class="sys-person">'+C.avatar(initials,tone)+'<div><b>'+C.esc(name)+'</b><small>'+C.esc(sub)+'</small></div></div>'
};

BiteViews.NOTIFICATION_PERMISSION=(s,C,state)=>`
  ${SYS.head(C,'A little heads-up','Good timing.<br>Less noise.','Replies from your people. Dinner plans you chose. Nothing trying to guilt you into cooking.')}
  <div class="permission-visual sys-permission-art">
    <span class="sys-bell-orbit">${C.icon('bell')}</span>
    <div class="permission-preview"><span class="brand-symbol">f.</span><div><div class="between"><b>FeedMe</b><span class="small muted">now</span></div><p class="small">You have a new reply.</p></div></div>
    <div class="sys-preview-caption">Lock-screen preview · example</div>
  </div>
  <div class="check-list"><div>${C.icon('check')}Choose the updates you actually want</div><div>${C.icon('check')}Set quiet hours for your time zone</div><div>${C.icon('lock')}Food preferences stay off your lock screen</div></div>
  <div class="stack">${C.action(s,'Enable notifications',{text:'Choose my notifications',kind:'primary',icon:'bell'})}${C.action(s,'Maybe later',{text:'Not now',kind:'ghost'})}</div>
  <p class="quiet-note">Your device asks for permission separately. You can change your mind anytime.</p>
  ${C.action(s,'Open device settings',{kind:'row',icon:'settings'})}
`;

BiteViews.SETTINGS=(s,C,state)=>`
  <div class="settings-profile">${C.avatar('YU','blue')}<div><div class="eyebrow">Your corner of the club</div><h2>You, on your terms.</h2></div></div>
  ${C.action(s,'Edit profile',{kind:'row',icon:'user'})}
  <section class="settings-section"><h2>Make the kitchen yours</h2>${C.action(s,'Food preferences',{kind:'row',icon:'leaf'})}${C.action(s,'Kitchen equipment',{kind:'row',icon:'sliders'})}${C.action(s,'Taste memory',{kind:'row',icon:'spark'})}${C.action(s,'Household',{kind:'row',icon:'people'})}</section>
  <section class="settings-section"><h2>Your space. Your rules.</h2>${C.action(s,'Privacy and safety',{kind:'row',icon:'shield'})}${C.action(s,'Notifications',{kind:'row',icon:'bell'})}${C.action(s,'Devices and sessions',{kind:'row',icon:'lock'})}</section>
  <section class="settings-section"><h2>The practical stuff</h2>${C.action(s,'Purchases',{kind:'row',icon:'bookmark'})}${C.action(s,'Help',{kind:'row',icon:'chat'})}${C.action(s,'Terms and privacy',{kind:'row',icon:'book'})}</section>
  <div class="sys-settings-footer"><span class="sys-mini-brand">feedme.</span><span>Less pressure.<br>More dinner.</span></div>
  ${C.action(s,'Log out',{kind:'ghost',icon:'logout'})}
`;

BiteViews.PRIVACY=(s,C,state)=>`
  ${SYS.head(C,'Privacy & control','Your kitchen.<br>Your boundaries.','Keep what is personal, personal. Your cooking preferences are never a social profile.')}
  <div class="surface-lime sys-privacy-hero"><span class="sys-large-icon">${C.icon('shield')}</span><div><b>Private by design.</b><p class="small">There is no public discovery feed. You choose who gets a seat at your table.</p></div></div>
  ${C.section('Who can reach you',C.field(s,'contact',{appearance:'chips'})+C.field(s,'coordinationInvites')+C.field(s,'socialDiscoveryVisible'))}
  <p class="quiet-note">Hiding social entry points does not delete posts or change their audiences.</p>
  ${C.action(s,'Save privacy choices',{kind:'primary',icon:'check'})}
  ${C.section('Sharing & memory',C.action(s,'Edit default audience',{kind:'row',icon:'people'})+C.action(s,'Manage remembered preferences',{kind:'row',icon:'spark'})+C.action(s,'Blocked accounts',{kind:'row',icon:'shield'}))}
  ${C.section('Your data belongs to you',C.action(s,'Export my data',{kind:'row',icon:'upload'})+C.action(s,'Privacy policy',{kind:'row',icon:'book'})+C.action(s,'Delete my account',{kind:'row',icon:'trash'}))}
`;

BiteViews.BLOCKED=(s,C,state)=>`
  ${SYS.head(C,'Safety settings','Boundaries,<br>without the drama.','Blocked accounts cannot start new interactions with you. We do not send them a block notification.')}
  <div class="sys-count-band"><span class="metric">01</span><div><b>Blocked account</b><p class="small muted">Example list for this prototype</p></div>${C.icon('shield')}</div>
  <div class="surface sys-block-card">${SYS.identity(C,'AL','Alex Lane','Selected blocked account','coral')}<div class="divider"></div><p class="small muted">Unblocking does not restore old invitations, memberships or audience access.</p>${C.action(s,'Unblock selected account',{text:'Unblock Alex',kind:'secondary'})}</div>
  ${SYS.note(C,'lock','This list is visible only to you.')}
  ${C.section('Manage a selected account','<p class="quiet-note">For a profile opened from the app. The final action uses the selected account ID.</p>'+C.action(s,'Block selected account',{kind:'danger',icon:'shield'}))}
`;

BiteViews.REPORT=(s,C,state)=>`
  ${SYS.head(C,'We’re listening','Something<br>not okay?','Tell us what happened. You can report a concern without explaining it to the other person.')}
  <div class="sys-target"><span class="sys-target-icon">${C.icon('shield')}</span><div><b>Selected post or account</b><p class="small muted">The exact reference travels with your report.</p></div></div>
  ${C.field(s,'reason',{appearance:'chips'})}${C.field(s,'context',{label:'Anything else we should know?',placeholder:'A little context helps our team review this.'})}
  ${C.field(s,'block',{label:'Also block this account'})}
  ${SYS.note(C,'lock','Your identity is not shown to the reported person. Our moderation team can review the relevant evidence.')}
  <div class="stack">${C.action(s,'Submit report',{kind:'primary',icon:'shield'})}${C.action(s,'Cancel',{kind:'ghost'})}</div>
`;

BiteViews.DELETE_POST=(s,C,state)=>`
  <div class="sys-safety-mark">${C.icon('trash')}</div>
  ${SYS.head(C,'Post controls','Delete this post?','This removes the selected post from its audiences and starts removal of its attached media.')}
  <div class="sys-delete-preview">${C.photo('bowl','','Example of the selected meal post')}<div><span class="eyebrow">Selected post</span><b>Tonight’s little win</b><small>Synthetic post preview</small></div></div>
  <div class="warning-block"><h2>Before it goes</h2><p class="small">Deleting a post cannot pull back screenshots or recipe copies someone was already allowed to save. Safety or legal recalls are handled separately.</p></div>
  ${C.field(s,'confirm')}
  <div class="stack">${C.action(s,'Delete post',{kind:'danger',icon:'trash'})}${C.action(s,'Keep post',{kind:'primary',text:'Keep my post'})}</div>
  <p class="quiet-note">You will confirm the exact post before deletion is sent.</p>
`;

BiteViews.DELETE_ACCOUNT=(s,C,state)=>`
  ${SYS.head(C,'Account deletion','Your choice.<br>Clear consequences.','You can request deletion here. We will ask you to verify it is really you.')}
  <div class="warning-block"><h2>What this means</h2>${SYS.line(C,'user','Your account closes','You lose access to your profile and private kitchen data.')}${SYS.line(C,'trash','Your content is processed for removal','Some records may be kept where legally required. Authorized recipe-copy policy still applies.')}</div>
  <div class="surface"><div class="row">${C.icon('bookmark')}<b>Have a store subscription?</b></div><p class="quiet-note">Deleting FeedMe does not cancel store billing. Manage your subscription with the store first.</p>${C.action(s,'Manage my subscription first',{kind:'secondary'})}</div>
  <div class="sys-danger-form">${C.field(s,'confirmText',{placeholder:'DELETE'})}${C.action(s,'Request account deletion',{kind:'danger',icon:'trash'})}</div>
  ${C.action(s,'Cancel',{text:'Keep my account',kind:'ghost'})}
`;

BiteViews.NOTIFICATIONS=(s,C,state)=>`
  ${SYS.head(C,'Your signal, your volume','Keep the good<br>pings only.','No cooking guilt. No penalty reminders. Just updates you choose.')}
  <div class="sys-device-status"><div>${C.icon('bell')}<b>Device permission</b></div>${C.chip('Check on device','lilac')}</div>
  <div class="surface sys-notification-card">${C.field(s,'replies')}${C.field(s,'invites')}${C.field(s,'pacts')}</div>
  ${C.section('Quiet looks good on you',C.field(s,'quiet',{label:'Quiet hours · device time zone'})+'<div class="sys-night-track"><span>22:00</span><div><i></i><i></i><i></i><i></i><i></i></div><span>08:00</span></div><p class="quiet-note">Saved with your time zone. Your example schedule is editable.</p>')}
  ${C.action(s,'Save notification choices',{kind:'primary',icon:'check'})}
  ${C.action(s,'Enable on this device',{kind:'row',icon:'bell'})}${C.action(s,'Open device notification settings',{kind:'row',icon:'settings'})}
  ${SYS.note(C,'lock','Private food or dietary details never belong in lock-screen notifications.')}
`;

BiteViews.SESSIONS=(s,C,state)=>`
  ${SYS.head(C,'Account security','Who’s signed in?<br>You should know.','Review devices with access to your account. Recognize every one?')}
  <div class="surface sys-session"><div class="row"><span class="sys-device-icon">${C.icon('user')}</span><div><b>This device</b><p class="small muted">Current app session</p></div></div>${C.chip('Current','lime')}</div>
  <div class="surface sys-session-secondary"><div class="row"><span class="sys-device-icon">${C.icon('lock')}</span><div><b>Another device</b><p class="small muted">Example recent session · selected</p></div></div><p class="quiet-note">Only approximate device details are shown. No precise location is implied.</p>${C.action(s,'Revoke selected session',{text:'Sign out this device',kind:'secondary',icon:'logout'})}</div>
  ${SYS.note(C,'shield','Do not recognize a session? Revoke access, then secure your sign-in account.')}
  <div class="divider"></div>${C.action(s,'Sign out everywhere',{kind:'danger',icon:'logout'})}
  <p class="quiet-note">This includes the device you are using now.</p>
`;

BiteViews.HOUSEHOLDS=(s,C,state)=>`
  ${SYS.head(C,'Shared kitchen','Same kitchen.<br>Different cravings.','A little coordination for the people you cook with. Separate from your social circles.')}
  <div class="sys-household-banner"><div class="avatar-stack">${C.avatar('YU','blue')}${C.avatar('JO','coral')}${C.avatar('SA','lilac')}</div><h2>Our kitchen</h2><p>Example household · 3 seats at the table</p><span class="sticker">Room for everyone’s taste.</span></div>
  ${C.action(s,'Members',{kind:'row',icon:'people'})}${C.action(s,'Shared meal preferences',{kind:'row',icon:'sliders'})}${C.action(s,'View plan',{kind:'row',icon:'bookmark'})}
  ${C.section('Starting a household?',C.field(s,'name',{label:'Give your kitchen a name'})+C.action(s,'Create household',{kind:'primary',icon:'plus'}))}
  ${SYS.note(C,'lock','Everyone chooses which personal requirements to share. No private dietary reasons are shown.')}
  <details class="sys-fold"><summary>Leave or close this household</summary><div class="stack">${C.action(s,'Leave household',{kind:'secondary',icon:'logout'})}${C.action(s,'Dissolve my household',{kind:'danger',icon:'trash'})}</div><p class="quiet-note">Closing the household requires ownership and confirmation.</p></details>
`;

BiteViews.HOUSEHOLD_MEMBER=(s,C,state)=>`
  ${SYS.head(C,'Our kitchen · people','Meet your<br>dinner team.','Invite people deliberately. Being in a household never adds them to your circles.')}
  <div class="sys-member-list"><div class="between">${SYS.identity(C,'YU','You','Household owner','blue')}${C.chip('Owner','lime')}</div><div class="between">${SYS.identity(C,'JO','Jo Rivera','Example member · selected','coral')}${C.chip('Member')}</div><div class="between">${SYS.identity(C,'SA','Sam Quinn','Example member','lilac')}${C.chip('Member')}</div></div>
  <div class="surface-lime sys-invite-card"><div class="row">${C.icon('link')}<h2 class="section-title">A seat at your table</h2></div><p class="small">Create an invitation, then choose where to share it. The link does not auto-enroll anyone.</p><div class="stack">${C.action(s,'Create member invitation',{kind:'primary',text:'Create invitation',icon:'plus'})}${C.action(s,'Share invitation',{kind:'secondary',icon:'send'})}</div></div>
  ${C.action(s,'Accept household invitation',{kind:'row',icon:'check'})}
  <details class="sys-fold"><summary>Manage selected member</summary>${C.action(s,'Remove selected member',{text:'Remove Jo from household',kind:'danger'})}<p class="quiet-note">Owner permission is checked before any membership changes.</p></details>
`;

BiteViews.HOUSEHOLD_PREFS=(s,C,state)=>`
  ${SYS.head(C,'Shared kitchen setup','The common<br>ingredients.','Set the basics you share. Keep the personal parts in your control.')}
  <div class="surface sys-shared-defaults"><div class="row sys-card-head">${C.icon('people')}<h2>Household defaults</h2>${C.chip('Owner edits')}</div>${C.field(s,'servings')}${C.field(s,'equipment',{placeholder:'Stove, one pan, microwave…'})}${C.action(s,'Save shared defaults',{kind:'primary'})}</div>
  <div class="surface-lilac sys-sharing-choice"><div class="row sys-card-head">${C.icon('lock')}<h2>Your personal choice</h2></div><p class="small">Only the requirements you select are used for shared planning. Their private reasons stay yours.</p>${C.field(s,'sharedRequirements',{label:'Requirements I choose to share',placeholder:'Add only what this household needs to know'})}${C.field(s,'sharingConsent')}${C.action(s,'Save my sharing choice',{kind:'secondary'})}</div>
  ${C.action(s,'Plan a household meal',{text:'Let’s find our dinner',kind:'primary',icon:'arrow'})}
  <p class="quiet-note">Everyone’s active requirements are checked when a meal is planned. Conflicts are explained, never silently ignored.</p>
`;

BiteViews.PACK_STORE=(s,C,state)=>`
  <div class="between"><span class="eyebrow">Extras, if they help</span>${C.chip('Optional','lime')}</div>
  ${C.hero({title:'Made for<br>your kind of night.',copy:'Authored collections for real-life kitchens. Core cooking stays free.'})}
  ${C.link(s,'View a pack','<div class="sys-pack-feature">'+C.photo('wrap','','Cucumber wrap from the example small-kitchen collection')+'<span class="sticker">Small kitchen.<br>Big possibility.</span><div class="sys-pack-caption"><span class="eyebrow">Example situation pack</span><h2>Counter space?<br>Overrated.</h2><div class="between"><span class="small">Small kitchen evenings</span>'+C.icon('arrow')+'</div></div></div>')}
  <div class="sys-pack-grid"><div class="surface-lilac"><span class="sys-pack-number">01</span><b>Useful over endless.</b><p class="small">Clear equipment and preparation expectations.</p></div><div class="surface-lime"><span class="sys-pack-number">02</span><b>Review before release.</b><p class="small">Published packs carry reviewer and version details.</p></div></div>
  <p class="quiet-note">Illustrative catalog. Prices, availability and review records load from the live store and catalog.</p>
  ${C.action(s,'Restore purchases',{kind:'row',icon:'repeat'})}${C.action(s,'My purchases',{kind:'row',icon:'bookmark'})}
`;

BiteViews.PACK_DETAIL=(s,C,state)=>`
  <div class="sys-pack-detail-image">${C.photo('wrap','','Example cucumber wrap in the small kitchen pack')}<span class="chip chip-lime">Situation pack · example</span></div>
  ${SYS.head(C,'Small kitchen evenings','Small space.<br>Full plate.','Simple setups, flexible ingredients and fewer things waiting in the sink.')}
  <div class="chips">${C.chip('One-pan friendly','lilac')}${C.chip('Equipment listed')}${C.chip('No health promises')}</div>
  ${C.section('Know what you’re opening','<div class="surface">'+SYS.line(C,'book','Versioned recipes','A published pack lists included recipe versions and substitutions.')+SYS.line(C,'shield','Review details','Reviewer identity, scope and preparation evidence are visible before purchase.')+'</div>')}
  <div class="stack">${C.action(s,'Try a free sample',{kind:'primary',text:'Try the free sample',icon:'play'})}${C.action(s,'Unlock this pack',{text:'See purchase options',kind:'secondary',icon:'arrow'})}${C.action(s,'Open owned pack',{kind:'secondary',icon:'book'})}</div>
  <p class="quiet-note">Access depends on current server-confirmed ownership. This sample does not represent a live reviewed pack.</p>
  ${C.action(s,'Review purchase terms',{kind:'row',icon:'book'})}
`;

BiteViews.PAYWALL=(s,C,state)=>`
  <div class="plan-banner sys-paywall-poster"><span class="eyebrow">A little extra, on your terms</span><h2>Upgrade<br>your options.<br><span>Not the pressure.</span></h2><span class="sticker">The core club is still free.</span></div>
  <div class="surface sys-offer-card"><div class="between"><b>Selected store offering</b>${C.chip('Store pricing','lilac')}</div><p class="quiet-note">The live offer displays the exact localized price, billing period and included access before you confirm.</p><div class="sys-price-placeholder"><span>Price & terms</span><b>Load from your store</b></div><p class="small muted">No price or subscription is active in this visual prototype.</p></div>
  <div class="check-list"><div>${C.icon('check')}Buy only the extras you want</div><div>${C.icon('check')}Your core saved recipes stay accessible</div><div>${C.icon('check')}Access is confirmed by the server</div></div>
  <div class="stack">${C.action(s,'Purchase selected offer',{text:'Continue to store · demo',kind:'primary',icon:'arrow'})}${C.action(s,'Keep using the free app',{text:'I’m good with free',kind:'secondary'})}</div>
  ${C.action(s,'Restore purchases',{kind:'ghost',icon:'repeat'})}${C.action(s,'Terms and cancellation',{kind:'row',icon:'book'})}
`;

BiteViews.PURCHASE_STATUS=(s,C,state)=>`
  <div class="sys-purchase-visual"><div class="sys-purchase-orbit">${C.icon('clock')}</div><span class="sticker">No need to buy twice.</span></div>
  ${SYS.head(C,'Purchase status','Checking the<br>final ingredient.','The store and FeedMe are confirming your access. A store response alone does not unlock paid features.')}
  <div class="sys-pending-banner">${C.chip('Pending example','lilac')}<span>Nothing has been purchased in this prototype.</span></div>
  ${SYS.steps(C,[['Store response','Keep the original transaction reference.'],['Verify entitlement','FeedMe checks the transaction on the server.'],['Update your access','Paid features unlock only after confirmation.']])}
  <div class="stack">${C.action(s,'Check access',{kind:'primary',icon:'repeat'})}${C.action(s,'Continue using FeedMe',{kind:'secondary',text:'Keep cooking while we check'})}${C.action(s,'Get purchase help',{kind:'ghost',icon:'chat'})}</div>
  <p class="quiet-note">Do not start another purchase while this one is pending. Cancelled or failed transactions never grant access.</p>
`;

BiteViews.MANAGE_PLAN=(s,C,state)=>`
  ${SYS.head(C,'Purchases & access','Your club.<br>Your extras.','A clear view of what you own and where you manage it.')}
  <div class="sys-membership-card"><div class="between"><span class="eyebrow">FeedMe</span>${C.icon('spark')}</div><h2>Core club.</h2><p>Make dinner yours.</p><div class="between"><span class="small">Core cooking is available</span>${C.chip('Free','lime')}</div></div>
  <div class="surface sys-entitlement"><div class="row"><span class="sys-target-icon">${C.icon('bookmark')}</span><div><b>Paid access</b><p class="small muted">Not connected in this prototype</p></div></div><p class="quiet-note">Live purchases show the store, expiry, renewal state and server-confirmed entitlements here.</p></div>
  ${C.action(s,'Manage store subscription',{kind:'row',icon:'link'})}${C.action(s,'Restore purchases',{kind:'row',icon:'repeat'})}${C.action(s,'Browse packs',{kind:'row',icon:'book'})}${C.action(s,'Household settings',{kind:'row',icon:'people'})}${C.action(s,'Purchase help',{kind:'row',icon:'chat'})}
  ${SYS.note(C,'shield','Subscription changes happen in the original store. Your private saved recipes are not held hostage by an upgrade.')}
`;

BiteViews.EXPORT=(s,C,state)=>`
  <div class="sys-export-art"><div class="sys-file"><span>feedme.</span>${C.icon('upload')}<b>YOUR DATA</b><small>Private export</small></div><span class="sticker">Yours to take.</span></div>
  ${SYS.head(C,'Data portability','Your FeedMe,<br>to go.','Request a private copy of eligible account, cooking and sharing data.')}
  <div class="surface sys-export-scope"><div class="row"><b>What’s in the bag</b>${C.chip('Private')}</div><ul><li>Your profile and preferences</li><li>Your eligible recipes and cooking records</li><li>Your own sharing and account records</li></ul><p class="small muted">Other people’s private content is excluded according to the export policy.</p></div>
  <div class="stack">${C.action(s,'Request export',{kind:'primary',icon:'upload'})}${C.action(s,'Check export status',{kind:'secondary',icon:'repeat'})}${C.action(s,'Download my export',{kind:'secondary',icon:'arrow'})}</div>
  ${SYS.note(C,'lock','A recent sign-in is required. The download appears only when your export is ready and expires for your privacy.')}
`;

BiteViews.SUPPORT=(s,C,state)=>`
  ${SYS.head(C,'We’ve got your back','Need a hand?<br>Pull up a chair.','Account questions, purchase hiccups or a concern you reported. Tell us where you got stuck.')}
  <div class="sys-help-bubble">${C.avatar('FM','blue')}<div><span class="eyebrow">FeedMe support</span><p>What can we help you untangle?</p></div>${C.icon('chat')}</div>
  ${C.field(s,'message',{label:'What happened?',placeholder:'What were you trying to do? Please leave out passwords and payment details.'})}
  ${C.action(s,'Send support request',{kind:'primary',icon:'send'})}
  <p class="quiet-note">When submitted, a support receipt appears here. This demo does not send a message or create a case.</p>
  ${C.section('The useful shortcuts',C.action(s,'Account and privacy',{kind:'row',icon:'shield'})+C.action(s,'Community rules',{kind:'row',icon:'people'})+C.action(s,'Back to cooking',{kind:'row',icon:'arrow'}))}
`;

BiteViews.UNAVAILABLE=(s,C,state)=>`
  <div class="sys-unavailable-art"><span class="sys-empty-plate"></span><span class="sticker">A different route?</span></div>
  ${SYS.head(C,'A little detour','This one isn’t<br>on the menu.','It may be unavailable, no longer shared with you, or not supported right now.')}
  <div class="surface sys-unavailable-note"><b>You still have options.</b><p class="quiet-note">Try another meal or head back to Today. We will not reveal private content just to explain a missing link.</p></div>
  <div class="stack">${C.action(s,'Choose another meal',{kind:'primary',icon:'spark'})}${C.action(s,'Back to Today',{kind:'secondary',icon:'home'})}</div>
  ${C.action(s,'Get help',{kind:'row',icon:'chat'})}${C.action(s,'Log in again',{kind:'row',icon:'user'})}
`;

BiteViews.OFFLINE=(s,C,state)=>`
  <div class="sys-offline-art"><span class="sys-offline-dot"></span><div class="sys-offline-path"><i></i><i></i><i></i></div><span class="sys-offline-phone">${C.icon('book')}</span><span class="sticker">Dinner can still happen.</span></div>
  ${SYS.head(C,'Connection paused','Offline.<br>Still in the kitchen.','Your connection is taking a break. Safe, already-downloaded recipes and an active cooking session may still be available.')}
  <div class="surface-lime sys-offline-options"><div>${C.icon('book')}<span>Downloaded private recipes</span></div><div>${C.icon('clock')}<span>Cached steps & active timers</span></div></div>
  <div class="stack">${C.action(s,'Open saved meals',{kind:'primary',icon:'book'})}${C.action(s,'Resume cooking',{kind:'secondary',icon:'play'})}${C.action(s,'Try connection again',{kind:'ghost',icon:'repeat'})}</div>
  <p class="quiet-note">Sharing, fresh access checks and purchases need a connection. Unsynced work is never shown as successfully published.</p>
`;

BiteViews.CONFIRM_ACTION=(s,C,state)=>`
  <div class="sys-confirm-background"><span class="sys-confirm-ghost"></span><span class="sys-confirm-ghost"></span><span class="sys-confirm-ghost"></span></div>
  <div class="sys-confirm-sheet"><span class="sys-sheet-handle"></span><div class="sys-safety-mark">${C.icon('shield')}</div>${SYS.head(C,'One last check','Confirm<br>this change?',state.pending?'Review the selected command and its consequences before sending it.':'The exact action and target appear here when a change needs confirmation.')}
  <div class="sys-confirm-target"><span class="eyebrow">Selected action</span><b>${C.esc(state.pending?.label||state.pending?.action?.label||'No pending action selected')}</b><p class="small muted">Permission is checked again on the server. A confirmation never bypasses access rules.</p></div>
  <div class="stack">${C.action(s,'Confirm change',{kind:'danger',icon:'check'})}${C.action(s,'Cancel',{kind:'secondary',text:'Cancel · keep things as they are'})}</div></div>
`;

BiteViews.ADMIN_LOGIN=(s,C,state)=>`
  <div class="ops-login-layout"><div class="ops-login-poster"><span class="eyebrow">FeedMe / operations</span><h1>Good food.<br>Strong<br>foundations.</h1><span class="ops-login-star">✳</span><p>The workspace behind a safer,<br>more effortless kitchen.</p><div class="ops-login-footer">REVIEW · PROTECT · OPERATE</div></div>
  <div class="ops-login-form"><div class="big-symbol">${C.icon('shield')}</div><div class="eyebrow">Authorized staff only</div><h2>Welcome to<br>the other kitchen.</h2><p class="body-copy">Sign in with your workforce identity. Your staff role determines the tools and records you can access.</p><div class="stack">${C.action(s,'Sign in with staff SSO',{kind:'primary',icon:'lock'})}${C.action(s,'Return to member app',{kind:'ghost',icon:'arrow'})}</div><div class="sys-note">${C.icon('lock')}<p>Workforce SSO + MFA. Member accounts cannot become staff accounts. Purpose-limited access is audited.</p></div></div></div>
`;

BiteViews.ADMIN_HOME=(s,C,state)=>`
  ${SYS.opsHead(C,'Operations / overview','The kitchen behind the club.','Role-filtered queues. Clear ownership. Deliberate actions.')}
  <div class="ops-fixture-notice">${C.icon('shield')}<span>Illustrative work queues below · not connected to production systems</span></div>
  <div class="ops-grid">${SYS.stat(C,'Content review','08','Example revisions awaiting an independent reviewer','ops-stat-blue')}${SYS.stat(C,'Safety queue','03','Example cases requiring triage','ops-stat-coral')}${SYS.stat(C,'Release gates','02','Example staged changes pending approval','ops-stat-lime')}</div>
  <div class="ops-home-grid"><section class="surface"><div class="ops-card-heading">${C.icon('book')}<div><h2>Content studio</h2><p>Keep every recipe traceable.</p></div></div>${C.action(s,'Recipe drafts',{kind:'row',icon:'book'})}${C.action(s,'Review queue',{kind:'row',icon:'check'})}${C.action(s,'Substitution rules',{kind:'row',icon:'repeat'})}${C.action(s,'Pack catalog',{kind:'row',icon:'bookmark'})}</section><section class="surface"><div class="ops-card-heading">${C.icon('shield')}<div><h2>Trust & operations</h2><p>Intervene with context and care.</p></div></div>${C.action(s,'Moderation cases',{kind:'row',icon:'shield'})}${C.action(s,'Incident console',{kind:'row',icon:'bolt'})}${C.action(s,'Release flags',{kind:'row',icon:'sliders'})}${C.action(s,'Audit log',{kind:'row',icon:'lock'})}</section></div>
`;

BiteViews.ADMIN_RECIPE=(s,C,state)=>`
  ${SYS.opsHead(C,'Content studio / recipe draft','Small edits. A traceable recipe.','Draft revision · example cucumber wrap · source licensing required before publication')}
  <div class="ops-split"><div><div class="surface ops-editor"><div class="between"><h2>Recipe content</h2>${C.chip('Draft','lilac')}</div>${C.field(s,'title')}${C.field(s,'steps',{placeholder:'Add quantities, ingredients and step-by-step instructions.'})}<div class="ops-editor-tools">${C.action(s,'Save draft',{kind:'primary',icon:'check'})}${C.action(s,'Preview member recipe',{kind:'secondary',icon:'play'})}</div></div>
  <section class="ops-information"><div class="eyebrow">Structured production data</div><p>Ingredient units, equipment, effort timing and source licensing are validated before submission. This visual editor shows the registry’s draft fields.</p></section></div>
  <aside class="ops-recipe-preview">${C.photo('wrap','','Example recipe art for a draft, not reviewed evidence')}<div class="food-copy"><div class="chips">${C.chip('Member preview','lime')}${C.chip('Not published')}</div><h2>Simple cucumber wrap</h2><p class="small muted">Immutable revisions let changes move through review without rewriting published content.</p></div></aside></div>
  <div class="ops-decision-footer"><p>${C.icon('shield')}The draft author cannot approve their own review.</p>${C.action(s,'Submit for review',{kind:'primary',icon:'arrow'})}</div>
`;

BiteViews.ADMIN_REVIEW=(s,C,state)=>`
  ${SYS.opsHead(C,'Content studio / independent review','Evidence before publish.','Inspect the exact revision and record a reasoned decision. Approval and publication are separate actions.')}
  <div class="ops-split"><div><div class="surface ops-evidence"><div class="between"><h2>Cucumber wrap · revision 04</h2>${C.chip('In review','lilac')}</div><p class="small muted">Synthetic evidence summary · requires actual review records</p><div class="ops-diff"><span class="eyebrow">Revision change</span><del>“Cook until ready”</del><ins>Explicit preparation cue + step duration</ins></div>${SYS.line(C,'check','Preparation evidence','Confirm quantities, equipment and tested preparation cues.')}${SYS.line(C,'shield','Review scope','Verify source rights, ingredient rules and reviewer credentials.')}${SYS.line(C,'user','Independent reviewer','Author and reviewer identities must differ.')}</div></div>
  <div class="surface ops-review-form"><h2>Record your decision</h2>${C.field(s,'decision',{appearance:'chips'})}${C.field(s,'reason')}<div class="stack">${C.action(s,'Record review decision',{kind:'primary',icon:'check'})}${C.action(s,'Publish approved revision',{kind:'secondary',icon:'upload'})}</div><p class="quiet-note">Publication remains blocked unless all required review gates pass.</p></div></div>
  <div class="ops-decision-footer"><div class="ops-tools">${C.action(s,'Return to draft',{kind:'ghost',icon:'arrow'})}${C.action(s,'Recall revision',{kind:'danger',icon:'shield'})}</div><p>Recall requires confirmation and an auditable reason.</p></div>
`;

BiteViews.ADMIN_SUBSTITUTION=(s,C,state)=>`
  ${SYS.opsHead(C,'Content studio / substitution rules','A swap is a recipe change.','Directed, context-specific rules. Never an unchecked ingredient replacement.')}
  <div class="ops-swap-visual"><div><span class="eyebrow">Source context</span><h2>Original ingredient</h2><p>Recipe version + quantity + purpose</p></div><span class="ops-swap-arrow">${C.icon('arrow')}</span><div><span class="eyebrow">Reviewed target</span><h2>Alternative ingredient</h2><p>Adjusted quantities + steps + timing</p></div>${C.chip('Draft rule','lilac')}</div>
  <div class="ops-split"><div class="surface ops-editor"><h2>Transformation definition</h2>${C.field(s,'rule',{placeholder:'Define the source and target, allowed recipe contexts, quantity conversion, changed steps and limits.'})}<div class="ops-editor-tools">${C.action(s,'Save substitution draft',{kind:'primary',icon:'check'})}${C.action(s,'Approve and activate',{kind:'secondary',icon:'shield'})}</div></div>
  <div class="surface"><h2 class="section-title">Before this goes live</h2>${SYS.steps(C,[['Context match','Pin compatible recipes and ingredient roles.'],['Requirement check','Re-evaluate exclusions and equipment needs.'],['Independent review','Store evidence, reviewer and immutable version.']])}</div></div>
  <div class="ops-decision-footer"><p>${C.icon('shield')}Disabling prevents future use. Existing plans are revalidated.</p>${C.action(s,'Disable substitution',{kind:'danger'})}</div>
`;

BiteViews.ADMIN_REPORTS=(s,C,state)=>`
  ${SYS.opsHead(C,'Trust & safety / triage','Context first. Then action.','Minimized evidence previews keep private content out of the queue until it is needed.')}
  <div class="ops-queue-summary"><div>${C.chip('Selected · BC-104','lilac')}<span>Example queue</span></div><div><span class="ops-status-dot"></span><span>Priority is illustrative, not a live service measure</span></div></div>
  ${SYS.table(C,['Case','Concern','Priority','Assignment','Next step'],[
    ['<b>BC-104</b><small>Selected example</small>','Unsafe content',C.chip('Review first','coral'),'Unassigned',C.action(s,'Open case',{kind:'primary',text:'Review case',icon:'arrow'})],
    ['<b>BC-103</b><small>Example record</small>','Harassment',C.chip('Standard'),'Assigned','<span class="muted">Evidence restricted</span>'],
    ['<b>BC-102</b><small>Example record</small>','Spam',C.chip('Standard'),'Unassigned','<span class="muted">Awaiting triage</span>']
  ])}
  <div class="ops-tools">${C.action(s,'Claim selected case',{kind:'secondary',icon:'user'})}${C.action(s,'Review audit',{kind:'ghost',icon:'lock'})}</div>
  <div class="ops-principle-strip">${SYS.line(C,'lock','Need-to-know access','Reporter identity is protected from the reported person.')}${SYS.line(C,'shield','Reasoned decisions','Every intervention records a policy reason and actor.')}${SYS.line(C,'repeat','Appeal-aware','Preserve decision history and reversible actions where possible.')}</div>
`;

BiteViews.ADMIN_CASE=(s,C,state)=>`
  ${SYS.opsHead(C,'Trust & safety / case BC-104','Review the concern, not the person.','Selected example case · staff purpose and scope checked before evidence access')}
  <div class="ops-split"><div class="surface ops-case-evidence"><div class="between"><h2>Evidence packet</h2>${C.chip('Restricted','lilac')}</div><div class="ops-evidence-placeholder">${C.icon('shield')}<b>Minimized content preview</b><p>Reported content loads only for an authorized case reviewer. No real user content is present in this prototype.</p></div><div class="ops-case-facts"><div><span>Reported reason</span><b>Unsafe content · example</b></div><div><span>Reporter</span><b>Protected</b></div><div><span>Current action</span><b>No action taken</b></div></div></div>
  <div class="surface ops-review-form"><h2>Decision & rationale</h2>${C.field(s,'reason',{placeholder:'Identify the applicable rule and relevant evidence. Do not include unnecessary personal information.'})}<div class="stack">${C.action(s,'Remove reported content',{kind:'danger',icon:'trash'})}${C.action(s,'Dismiss report',{kind:'secondary',icon:'check'})}${C.action(s,'Restrict account',{kind:'danger',icon:'shield'})}</div></div></div>
  <div class="ops-decision-footer"><p>${C.icon('lock')}Destructive actions require a scoped confirmation.</p><div class="ops-tools">${C.action(s,'Review appeal',{kind:'secondary',icon:'repeat'})}${C.action(s,'Back to queue',{kind:'ghost',icon:'arrow'})}</div></div>
`;

BiteViews.ADMIN_AUDIT=(s,C,state)=>`
  ${SYS.opsHead(C,'Operations / audit trail','Every action leaves a receipt.','Append-only records. Redacted payloads. Reading this trail is itself audited.')}
  <div class="surface ops-audit-search">${C.field(s,'query',{placeholder:'Search an exact target or trace ID'})}${C.action(s,'Find records',{kind:'primary',icon:'search'})}${C.action(s,'Request scoped export',{kind:'secondary',icon:'upload'})}</div>
  <div class="ops-table-title"><h2>Example event trail</h2>${C.chip('Redacted fixture','lilac')}</div>
  ${SYS.table(C,['Sequence','Actor / purpose','Action','Target','Outcome'],[
    ['<span class="ops-mono">000184</span>','<b>Staff reviewer</b><small>Content review</small>','review.recorded','<span class="ops-mono">recipe_version_04</span>',C.chip('Recorded','lime')],
    ['<span class="ops-mono">000183</span>','<b>Moderator</b><small>Case investigation</small>','case.evidence_read','<span class="ops-mono">BC-104</span>',C.chip('Audited')],
    ['<span class="ops-mono">000182</span>','<b>Release operator</b><small>Canary preparation</small>','flag.change_staged','<span class="ops-mono">F32</span>',C.chip('Staged','lilac')]
  ])}
  <div class="ops-audit-footer"><div>${C.icon('lock')}<span>Payloads omit secrets and unnecessary personal data.</span></div><p class="small muted">Exports require an explicit scope and role. No live records are loaded.</p></div>
`;

BiteViews.ADMIN_FLAGS=(s,C,state)=>`
  ${SYS.opsHead(C,'Release operations / flags','Roll out with a way back.','Server-side authorization remains in force at every rollout percentage.')}
  <div class="ops-rollout-band"><div><span class="eyebrow">Example rollout</span><strong>5<span>%</span></strong></div><div><div class="progress-track"><span style="width:5%"></span></div><div class="between small"><span>Canary cohort</span><span>Full eligibility</span></div></div>${C.chip('Staged · not applied','lilac')}</div>
  <div class="ops-split"><div class="surface ops-flag-form"><div class="two-col">${C.field(s,'feature')}${C.field(s,'percentage')}</div>${C.field(s,'reason')}<div class="ops-editor-tools">${C.action(s,'Stage flag change',{kind:'primary',icon:'sliders'})}${C.action(s,'Apply approved change',{kind:'secondary',icon:'check'})}</div></div>
  <div class="surface"><h2 class="section-title">Change control</h2>${SYS.steps(C,[['Stage the proposal','Feature, platforms, versions and cohort are explicit.'],['Pass the gates','Required checks and approval policy are enforced.'],['Observe and revert','Watch success, errors and safety indicators.']])}</div></div>
  <div class="ops-decision-footer"><p>${C.icon('shield')}Client visibility is not permission. Kill switches act on the server.</p>${C.action(s,'Disable selected feature',{kind:'danger',icon:'close'})}</div>
`;

BiteViews.ADMIN_PACK=(s,C,state)=>`
  ${SYS.opsHead(C,'Content studio / pack catalog','A collection with a paper trail.','Version the recipe manifest, review evidence and store mapping together.')}
  <div class="ops-split"><div class="surface ops-editor">${C.field(s,'title')}${C.field(s,'product',{label:'Store product mapping · stable identifier'})}<div class="ops-manifest"><div class="between"><h2>Pack manifest</h2>${C.chip('Draft','lilac')}</div><div class="ops-manifest-row">${C.icon('book')}<div><b>Version-pinned recipes</b><p>Every included revision must meet its review gates.</p></div></div><div class="ops-manifest-row">${C.icon('shield')}<div><b>Review evidence & rights</b><p>Required before the pack becomes discoverable for purchase.</p></div></div></div><div class="ops-editor-tools">${C.action(s,'Save pack draft',{kind:'primary',icon:'check'})}${C.action(s,'Publish reviewed pack',{kind:'secondary',icon:'upload'})}</div></div>
  <aside class="ops-pack-preview">${C.photo('noodles','','Example pack art featuring noodles')}<div><span class="eyebrow">Store preview · example</span><h2>Small kitchen.<br>Good evenings.</h2><p>Price and ownership are live store data, not authored marketing copy.</p></div></aside></div>
  <div class="ops-decision-footer"><p>${C.icon('bookmark')}Withdrawing from sale does not silently erase existing purchase access.</p>${C.action(s,'Withdraw pack from sale',{kind:'danger'})}</div>
`;

BiteViews.ADMIN_INCIDENT=(s,C,state)=>`
  ${SYS.opsHead(C,'Operations / incident console','Calm tools for loud moments.','Scoped commands. Explicit reasons. Approval policy for high-impact changes.')}
  <div class="ops-incident-banner">${C.icon('bolt')}<div><b>Demonstration incident workspace</b><p>No production health, customer records or live controls are connected.</p></div>${C.action(s,'Refresh service health',{kind:'secondary',icon:'repeat'})}</div>
  <div class="ops-service-grid"><div><span class="ops-status-neutral"></span><b>API & identity</b><small>Awaiting live health</small></div><div><span class="ops-status-neutral"></span><b>Media pipeline</b><small>Awaiting live health</small></div><div><span class="ops-status-neutral"></span><b>Outbox & workers</b><small>Awaiting live health</small></div></div>
  <div class="ops-split"><div class="surface ops-editor"><h2>Incident context</h2>${C.field(s,'reason',{placeholder:'Incident reference, exact scope, expected impact and rollback plan.'})}<div class="ops-editor-tools">${C.action(s,'Pause media publication',{kind:'danger',icon:'camera'})}${C.action(s,'Request outbox replay',{kind:'secondary',icon:'repeat'})}</div></div>
  <div class="surface"><h2 class="section-title">Before you intervene</h2>${SYS.steps(C,[['Confirm the scope','Select affected resources and the incident reference.'],['Get required approval','On-call role and policy checks happen server-side.'],['Verify the outcome','Inspect idempotent results, queues and audit records.']])}</div></div>
  <div class="ops-decision-footer"><p>${C.icon('lock')}A request is not a completed operation.</p><div class="ops-tools">${C.action(s,'Open release flags',{kind:'ghost',icon:'sliders'})}${C.action(s,'Inspect audit',{kind:'ghost',icon:'lock'})}</div></div>
`;

// Contract alignment and complete lifecycle controls. Called before action IDs are assigned.
export function refineScreens(screens){
 const byId=id=>screens.find(s=>s.id===id);
 screens.push({id:'PEOPLE_PICKER',title:'Choose your people',group:'Social',module:'circles',features:['F23','F28','F34','F35'],description:'Purpose-scoped member selector. Show only eligible mutual circle members or accepted event participants; selection is private until the user sends the invitation.',fields:[{id:'people',label:'Selected people',type:'text',example:'Meera',required:true,options:[]}],actions:[{label:'Use selected people',target:null,request:'LOCAL pickerResult',effect:'Return selected eligible account IDs to the requesting draft; server checks eligibility again at invitation creation.',branches:[['PACT_CREATE','Dinner Pact'],['POTLUCK_CREATE','Bring a Bit'],['INVITE','circle role selection']]},{label:'Cancel selection',target:null,request:'LOCAL pickerCancel',effect:'Return to requesting draft with previous recipients unchanged.',branches:[['PACT_CREATE','pact'],['POTLUCK_CREATE','potluck'],['INVITE','circle']]}],chrome:'focus',back:'CIRCLES'});
 screens.push({id:'CONFIRM_ACTION',title:'Confirm this change',group:'System',module:'platform',features:['F42','F49','F53'],description:'Reusable confirmation sheet showing the exact target, consequences and destructive command. This is a UI surface, not a separate backend operation.',fields:[],actions:[{label:'Confirm change',target:null,request:'LOCAL dispatchPendingCommand',effect:'Execute the originating action with its original target/version/idempotency key after explicit consent. Apply that action’s exact auth/API/error/offline contract; clear pending command after receipt.'},{label:'Cancel',target:null,request:'LOCAL cancelPendingCommand',effect:'Return to the originating screen; send no mutation and retain draft context.'}],chrome:'focus',back:'HOME'});
 const routeMap={
  'PUT /v1/pantry/items/{ingredientId}':'POST /v1/pantry/items',
  'PUT /v1/posts/{postId}/reactions/{reaction}':'POST /v1/posts/{postId}/reactions',
  'PUT /v1/blocks/{targetUserId}':'POST /v1/blocks',
  'PUT /v1/mutes/{targetType}/{targetId}':'POST /v1/mutes',
  'PUT /v1/polls/{pollId}/vote':'POST /v1/polls/{pollId}/votes',
  'PUT /v1/shortcuts/{shortcutId}/helpful':'POST /v1/shortcuts/{shortcutId}/helpful',
  'DELETE /v1/blocks/{targetUserId}':'DELETE /v1/blocks/{userId}',
  'PATCH /v1/notification-preferences':'PATCH /v1/notification-settings',
  'POST /v1/media/uploads':'POST /v1/media',
  'POST /v1/support-requests':'POST /v1/support',
  'POST /v1/posts/{postId}/recipe-requests':'POST /v1/recipe-requests',
  'POST /v1/invitations/{invitationId}/accept':'POST /v1/invitations/accept',
  'POST /v1/circles/{circleId}/invitations':'POST /v1/invitations',
  'POST /v1/households/{householdId}/invitations':'POST /v1/invitations',
  'POST /v1/households/{householdId}/join':'POST /v1/invitations/accept',
  'POST /v1/pacts/{pactId}/respond':'POST /v1/pacts/{pactId}/participation',
  'POST /v1/pacts/{pactId}/cancel':'DELETE /v1/pacts/{pactId}',
  'POST /v1/sos/{sosId}/close':'POST /v1/sos/{sosId}/resolve',
  'GET /v1/account/exports/{exportId}':'GET /v1/jobs/{jobId}',
  'POST /v1/admin/substitutions/{substitutionId}/approve':'POST /v1/admin/substitutions/{substitutionId}/reviews',
  'POST /v1/admin/substitutions/{substitutionId}/disable':'POST /v1/admin/substitutions/{substitutionId}/reviews',
  'POST /v1/admin/flags/{flagId}/disable':'PATCH /v1/admin/flags/{flagKey}',
 };
 const publicScreens=new Set(['AUTH_WELCOME','AUTH_SIGNUP','AUTH_LOGIN','AUTH_VERIFY','AUTH_RESET','AUTH_RESET_CONFIRM','AUTH_CALLBACK','LEGAL']);
 const privateScreens=new Set(['PROFILE_SETUP','PRIVACY','BLOCKED','REPORT','DELETE_POST','DELETE_ACCOUNT','NOTIFICATIONS','NOTIFICATION_PERMISSION','SESSIONS','EXPORT','SUPPORT']);
 for(const s of screens){
  s.auth=s.group==='Operations'?'staff':publicScreens.has(s.id)?'public':(['social','circles','conversations','coordination','commerce','safety'].includes(s.module)||privateScreens.has(s.id))?'member':'guest-or-member';
  for(const a of s.actions){
   a.request=routeMap[a.request]||a.request;
   a.request=a.request.replaceAll('/members/{memberId}','/members/{userId}').replaceAll('/items/{itemId}','/items/{savedRecipeId}');
   if(/^POST \/v1\/admin\/recipes\/\{recipeId\}\//.test(a.request))a.request=a.request.replace('/{recipeId}/','/{recipeId}/versions/{recipeVersionId}/');
   if(a.destructive)a.confirm=true;
   if(!a.auth)a.auth=s.auth;
  }
 }
 const add=(id,label,target,request,effect,extra={})=>byId(id).actions.push({label,target,request,effect,...extra});
 const change=(id,label,patch)=>Object.assign(byId(id).actions.find(a=>a.label===label),patch);
 // Explicit persistence and intent provenance: plain save does not mean enjoyed.
 change('RECIPE','Save recipe',{request:'POST /v1/saved-recipes',effect:'Create or return a private save from an authorized plan or reviewed recipeVersionId. Adding to a named collection is a separate command; a save alone is not positive taste feedback.'});
 change('MEAL_DONE','Make again',{request:'POST /v1/saved-recipes',effect:'Create/return the immutable private save and record explicit makeAgain intent using one idempotent command receipt; no public post or implicit sharing.'});
 change('REUSE','Find a next use',{request:'POST /v1/reuse-options'});
 change('CIRCLE_MEMBERS','Transfer ownership',{request:'POST /v1/circles/{circleId}/ownership',confirm:true});
 change('HOUSEHOLD_PREFS','Save shared defaults',{request:'PATCH /v1/households/{householdId}/preferences',guard:'Only household owner/admin may change shared equipment and servings. This does not edit another member’s private requirements.'});
 change('PROFILE_SETUP','Continue',{branches:[['SETTINGS','existing profile edit'],['FOOD_PREFS','first setup']]});
 change('FOOD_PREFS','Save preferences',{branches:[['SETTINGS','editing outside onboarding'],['EQUIPMENT','first setup']]});
 change('EQUIPMENT','Save kitchen setup',{branches:[['SETTINGS','editing outside onboarding'],['HOME','first setup']]});
 byId('PROFILE_SETUP').description+=' First-run and edit modes have distinct return destinations; a returning user does not repeat onboarding.';
 // Picker return is part of navigation state, not a hard-coded detour into recipe reading.
 for(const [id,label,purpose,ret] of [['ATTACH_RECIPE','Choose from my cookbook','recipe attachment','REVIEW_ATTACHMENT'],['SOS_DETAIL','Attach a saved recipe','shareable recipe suggestion','SOS_DETAIL'],['PACT_CREATE','Choose a recipe','pact source','PACT_CREATE'],['POLL_CREATE','Choose option A','poll option A','POLL_CREATE'],['POLL_CREATE','Choose option B','poll option B','POLL_CREATE']])change(id,label,{picker:{purpose,returnTo:ret,cancelTo:id},effect:`Open a scoped recipe picker for ${purpose}. On selection return to ${ret} with a permitted recipe/source reference; cancel returns to ${id} with its previous draft unchanged. Preserve draft/editPost/fulfillRequest mode throughout. Private-only saved source content cannot be reattached as redistributable text.`});
 add('COOKBOOK','Select this recipe',null,'LOCAL pickerResult','Visible only in picker context: validate the chosen recipe’s applicable rights, return a typed result to the origin and clear picker context.',{visibility:'picker mode only',branches:[['REVIEW_ATTACHMENT','attachment picker'],['SOS_DETAIL','SOS reply'],['PACT_CREATE','pact picker'],['POLL_CREATE','poll option picker']]});
 add('COOKBOOK','Cancel selection',null,'LOCAL pickerCancel','Visible only in picker context: restore originating draft unchanged.',{visibility:'picker mode only',branches:[['ATTACH_RECIPE','attachment picker'],['SOS_DETAIL','SOS reply'],['PACT_CREATE','pact picker'],['POLL_CREATE','poll option picker']]});
 add('COLLECTION','Add existing save',null,'POST /v1/collections/{collectionId}/items','Attach an existing owner-scoped savedRecipeId; unique collection/item relation prevents duplicates.');
 add('COLLECTION','Delete collection','COOKBOOK','DELETE /v1/collections/{collectionId}','Remove organization only; recipe saves persist. Require owner, version and explicit confirmation.',{confirm:true,destructive:true});
 add('COOKBOOK','Delete selected saved recipe',null,'DELETE /v1/saved-recipes/{savedRecipeId}','Delete this user’s private copy and collection memberships, not the original author’s content.',{confirm:true,destructive:true});
 add('FEEDBACK','Edit previous feedback',null,'PATCH /v1/feedback/{feedbackId}','Revise owned explicit feedback; update derived memory provenance and ranking revision.');
 add('FEEDBACK','Remove my feedback','MEAL_DONE','DELETE /v1/feedback/{feedbackId}','Retract source feedback and resulting unsupported memory influences; retain only legally required audit metadata.',{confirm:true,destructive:true});
 for(const id of ['POST','STORY'])add(id,'Remove my reaction',null,'DELETE /v1/posts/{postId}/reactions/me','Idempotently remove actor reaction; do not notify author of a removed reaction.');
 add('POST','Edit audience','AUDIENCE','LOCAL navigate','Owner-only live-post edit mode: use PATCH /v1/posts/{postId} after confirmation, never change an unsent draft instead.',{mode:'editPost'});
 add('POST','Change recipe-save permission','SAVE_PERMISSION','LOCAL navigate','Owner-only live-post edit mode; disclose prior allowed recipe copies persist while future grants change.',{mode:'editPost'});
 add('POST','Remove from my Plate',null,'PATCH /v1/posts/{postId}','Owner sets keepOnPlate=false; still-active Today placement remains until expiry. If neither placement remains, normal source access ends.',{confirm:true});
 add('POST','Edit caption',null,'PATCH /v1/posts/{postId}','Owner edits bounded caption/alt text with revision; content policy applies. Recipe revisions require a new confirmed attachment workflow.');
 add('AUDIENCE','Apply to existing post','POST','PATCH /v1/posts/{postId}','Owner-only live-post mode: atomically validate current membership, replace audience and bump access epoch; revoke future media delivery for removed recipients.',{visibility:'editPost mode only',confirm:true});
 add('SAVE_PERMISSION','Update existing post','POST','PATCH /v1/posts/{postId}','Change future recipe saving grant for an owned post with expected revision. Existing permitted private copies follow published retention policy.',{visibility:'editPost mode only',confirm:true});
 add('RECIPE_REQUEST','Answer with a recipe','ATTACH_RECIPE','LOCAL navigate','Author-only mode attaches a supported/licensed recipe to the pending request; user confirms before sending.',{visibility:'request author only',mode:'fulfillRequest'});
 add('RECIPE_REQUEST','Send recipe response','THREAD','POST /v1/recipe-requests/{recipeRequestId}/response','Author resolves request with decision=fulfill and the explicitly reviewed eligible recipeVersionId; recheck requester eligibility and redistribution rights. Direct viewing never creates a recipe-copy grant.',{visibility:'fulfillRequest confirmed mode only',clearMode:true});
 add('RECIPE_REQUEST','Decline request','THREAD','POST /v1/recipe-requests/{recipeRequestId}/response','Author sends decision=decline, closing the request privately without obligation to explain.',{visibility:'fulfillRequest mode only',clearMode:true});
 add('RECIPE_REQUEST','Cancel my request','THREAD','DELETE /v1/recipe-requests/{recipeRequestId}','Requester cancels only their own pending request.');
 add('CIRCLE','Delete my circle','CIRCLES','DELETE /v1/circles/{circleId}','Owner confirms dissolution. Revoke memberships/invites and circle-only visibility; preserve owned/private data according to lifecycle.',{confirm:true,destructive:true});
 add('CIRCLE','Edit circle details',null,'PATCH /v1/circles/{circleId}','Owner/admin updates permitted name/description fields with expected revision.');
 add('PACT_DETAIL','Leave this pact',null,'POST /v1/pacts/{pactId}/participation','Set own participation=left; exclude future thread sends/notifications as policy specifies while retaining private variants.');
 add('PACT_DETAIL','Propose a different time',null,'PATCH /v1/pacts/{pactId}','Create updated schedule revision; affected accepted participants must reconfirm rather than auto-commit.');
 add('PACT_DETAIL','Confirm changed plan',null,'POST /v1/pacts/{pactId}/participation','Accept the displayed pact revision; stale confirmation returns conflict.');
 add('POTLUCK_DETAIL','Join shared meal',null,'POST /v1/potlucks/{potluckId}/participation','Accept invitation and explicit sharing scope for this event.');
 add('POTLUCK_DETAIL','Leave shared meal',null,'POST /v1/potlucks/{potluckId}/participation','Withdraw own participation/contributions and invalidate confirmed plan; owner must transfer/cancel where necessary.',{confirm:true});
 add('POTLUCK_DETAIL','Cancel shared meal','CIRCLE','DELETE /v1/potlucks/{potluckId}','Owner cancels event, releases claims and prevents new activity; independently saved recipes persist.',{confirm:true,destructive:true});
 add('POTLUCK_DETAIL','Update shared meal details',null,'PATCH /v1/potlucks/{potluckId}','Owner changes servings/equipment/time with revision and requires plan reconfirmation.');
 add('SHORTCUT_DETAIL','Withdraw my shortcut','RECIPE','DELETE /v1/shortcuts/{shortcutId}','Author removes tip from future social display and flags private references unavailable; never edit reviewed recipe steps.',{confirm:true,destructive:true});
 add('SHORTCUT_DETAIL','Keep as my cooking note','COOK','PATCH /v1/cook-sessions/{sessionId}','Attach explicit community tip text as a private personal note; reviewed steps stay unchanged and the note retains its unreviewed label.');
 add('POLL_DETAIL','Remove my vote',null,'DELETE /v1/polls/{pollId}/votes/me','Remove actor vote before server-side closing time; closed polls reject changes.');
 add('THREAD','Unmute thread',null,'DELETE /v1/mutes/{muteId}','Remove chosen mute record; future delivery still obeys notification settings, block and audience checks.');
 add('EDIT_MEDIA','Discard this draft','HOME','DELETE /v1/post-drafts/{draftId}','Delete owner draft and revoke pending upload intents; purge temporary assets asynchronously.',{confirm:true,destructive:true,offline:'Delete local-only draft immediately; server-backed cleanup waits for online confirmation.'});
 add('HOUSEHOLDS','Leave household',null,'DELETE /v1/households/{householdId}/members/{userId}','Actor leaves explicit shared scope; private recipes/preferences remain. Last owner must transfer or dissolve.',{confirm:true});
 add('HOUSEHOLDS','Dissolve my household','SETTINGS','DELETE /v1/households/{householdId}','Owner confirms shared-scope dissolution; subscriptions are managed separately and private member data remains.',{confirm:true,destructive:true});
 add('HOUSEHOLD_PREFS','Save my sharing choice',null,'PATCH /v1/households/{householdId}/preferences/me','Actor updates only own selected requirement IDs and explicit consent for shared planning; unchecked consent withdraws sharing. Show generic group infeasibility without revealing individual causes.');
 byId('HOUSEHOLD_PREFS').fields.push({id:'sharingConsent',label:'Use my selected requirements for household meal planning',type:'checkbox',example:'false',required:false,options:[]},{id:'sharedRequirements',label:'My selected requirements to share',type:'text',example:'',required:false,options:[]});
 byId('EFFORT').fields.push({id:'activeMinutes',label:'Maximum active preparation minutes',type:'number',example:'5',required:false,options:[]});
 byId('REQUEST').fields.push({id:'baseMeal',label:'Meal already prepared (Improve mode)',type:'text',example:'Noodles',required:false,options:[]});
 byId('FEEDBACK').fields.push({id:'scope',label:'What is this feedback about?',type:'select',example:'Whole meal',required:false,options:['Whole meal','Ingredient','Texture','Preparation effort']},{id:'target',label:'Selected ingredient or texture',type:'text',example:'',required:false,options:[]});
 byId('COLLECTION_EDIT').fields.push({id:'items',label:'Choose saved meals',type:'select',example:'My easy wrap',required:false,options:['My easy wrap','My simple bowl']});
 add('COLLECTION_EDIT','Move selected recipe up',null,'LOCAL update','Adjust draft item order without a server write; Save order commits allowed advanced organization.');
 add('COLLECTION_EDIT','Move selected recipe down',null,'LOCAL update','Adjust draft item order with bounds; preserve recipe identity and rights.');
 add('COLLECTION_EDIT','Save order','COLLECTION','POST /v1/collections/{collectionId}/order','Commit exact unique ordered existing savedRecipeIds with collection revision and server capability check.');
 add('HOME','Resume my cooking session','COOK','LOCAL navigate','Resolve the pinned session. Online, check current recall status; offline, show last sync and unknown-current-recall limitation and never promise a valid safety lease. Show queued progress.');
 add('HOME','Connection and saved access','OFFLINE','LOCAL navigate','Open the offline banner to inspect locally cached private meals and retry connection; preserve the active draft.',{visibility:'offline banner only'});
 add('RECIPE','An ingredient is unavailable','ADAPT','LOCAL navigate','Preselect Keep the Vibe swap intent for the selected unavailable ingredient; never mutate already completed cooking steps.');
 add('ADAPT','Change effort','EFFORT','LOCAL navigate','Edit the adaptation draft and return to ADAPT with constraints preserved.',{picker:{purpose:'adaptation effort',returnTo:'ADAPT',cancelTo:'ADAPT'}});
 add('ADAPT','Change equipment','EQUIPMENT','LOCAL navigate','Edit current equipment/defaults explicitly; return to ADAPT and recompute affected eligibility.',{picker:{purpose:'adaptation equipment',returnTo:'ADAPT',cancelTo:'ADAPT'}});
 add('ADAPT','Change taste','TASTE','LOCAL navigate','Edit optional sensory intent; maintain all hard constraints.',{picker:{purpose:'adaptation taste',returnTo:'ADAPT',cancelTo:'ADAPT'}});
 add('TIMER','Resume timer',null,'EXTERNAL OS.scheduleLocalTimer','Use persisted remaining duration to create a fresh deadline and best-effort OS alert; never restart full duration silently.',{offline:'Available locally.'});
 add('TIMER','Cancel timer','COOK','EXTERNAL OS.cancelLocalTimer','Cancel the selected timer/notification and retain other active timers and cooking progress.',{offline:'Available locally.'});
 byId('TIMER').fields.push({id:'timerId',label:'Active timer',type:'select',example:'Current step',required:true,options:['Current step','Another active timer']});
 add('PRIVACY','Save privacy choices',null,'PATCH /v1/me/privacy','Update explicit contact permissions and future sharing defaults; existing post audiences remain unchanged.');
 byId('PRIVACY').fields.push({id:'contact',label:'Who may start a meal conversation?',type:'select',example:'Current circle members',required:true,options:['Current circle members','Nobody new']});
 byId('PRIVACY').fields.push({id:'coordinationInvites',label:'Allow meal invitations from eligible people',type:'checkbox',example:'true',required:false,options:[]},{id:'socialDiscoveryVisible',label:'Show optional social entry points',type:'checkbox',example:'true',required:false,options:[]});
 add('POST','Replace recipe attachment','ATTACH_RECIPE','LOCAL navigate','Owner-only edit: choose a permitted revision, review it, then explicitly commit to this post. Do not edit a composer draft instead.',{mode:'editPost',visibility:'post owner only'});
 add('POST','Remove recipe attachment',null,'PATCH /v1/posts/{postId}','Owner sends removeAttachment=true with expected version. Revoke future grants while preserving previously authorized copies under the lifecycle policy.',{visibility:'post owner only',confirm:true});
 add('REVIEW_ATTACHMENT','Replace on existing post','POST','PATCH /v1/posts/{postId}','Owner sends the confirmed attachment and expected post version. Atomically replace version-bound future save grant; existing permitted copies follow lifecycle policy.',{mode:'editPost',visibility:'editPost mode only',confirm:true});
 add('AUDIENCE','Save as my future default','SETTINGS','PATCH /v1/me/privacy','Settings-only mode saves future default audience IDs; does not mutate an existing draft or expand existing posts.',{visibility:'settings mode only'});
 add('SOS_DETAIL','Correct my open request',null,'PATCH /v1/sos/{sosId}','Owner edits only an open request with revision; mark existing suggestions as referencing the earlier context when needed.');
 add('SOS_DETAIL','Remove my reply',null,'DELETE /v1/sos/{sosId}/replies/{replyId}','Author removes their own reply; preserve limited moderation evidence under approved retention.',{confirm:true});
 for(const [id,label] of [['PACT_CREATE','Choose a friend'],['POTLUCK_CREATE','Choose participants']])add(id,label,'PEOPLE_PICKER','LOCAL navigate','Open an eligible-recipient picker and return selected IDs to this event draft.',{picker:{purpose:'event participants',returnTo:id,cancelTo:id}});
 add('CIRCLES','Use this circle',null,'LOCAL pickerResult','Visible only in circle-picker context; return selected circleId and current membership revision to the originating draft.',{visibility:'picker mode only',branches:[['SOS_CREATE','SOS audience'],['POTLUCK_CREATE','shared meal audience'],['AUDIENCE','post audience']]});
 for(const [id,label,ret] of [['SOS_CREATE','Choose circle','SOS_CREATE'],['POTLUCK_CREATE','Choose circle','POTLUCK_CREATE']])change(id,label,{picker:{purpose:'event circle',returnTo:ret,cancelTo:id}});
 // Shared social surfaces keep an explicit mode so they never dispatch the wrong resource command.
 change('PRIVACY','Edit default audience',{mode:'settings'});
 for(const [id,label] of [['AUDIENCE','Use this audience'],['SAVE_PERMISSION','Confirm permission'],['REVIEW_ATTACHMENT','Confirm attachment'],['ATTACH_RECIPE','Post without a recipe'],['PUBLISH_STATUS','Share with chosen circle']])change(id,label,{visibility:'draft mode only'});
 change('AUDIENCE','Recipe save permissions',{visibility:'publishing mode only'});
 change('AUDIENCE','Apply to existing post',{clearMode:true});
 change('SAVE_PERMISSION','Update existing post',{clearMode:true});
 change('REVIEW_ATTACHMENT','Replace on existing post',{clearMode:true});
 change('AUDIENCE','Save as my future default',{clearMode:true});
 change('PUBLISH_STATUS','Share with chosen circle',{clearMode:true});
 // A new publication explicitly enters draft mode; a picker or review keeps its originating mode.
 for(const s of screens)for(const a of s.actions)if(a.target==='CAPTURE')change(s.id,a.label,{mode:'draft'});
 for(const [id,label] of [['REVIEW_ATTACHMENT','Edit recipe details'],['SAVE_PERMISSION','Back to recipe details']])change(id,label,{preserveMode:true});
 change('REVIEW_ATTACHMENT','Remove attachment',{visibility:'draft mode only'});
 add('REVIEW_ATTACHMENT','Back to this post','POST','LOCAL navigate','Leave the existing attachment unchanged; discard only the uncommitted replacement selection.',{visibility:'editPost mode only',clearMode:true});
 add('AUDIENCE','Cancel audience edit','POST','LOCAL navigate','Return without changing the owned post audience.',{visibility:'editPost mode only',clearMode:true});
 add('AUDIENCE','Cancel default change','PRIVACY','LOCAL navigate','Return without changing the future default.',{visibility:'settings mode only',clearMode:true});
 add('SAVE_PERMISSION','Cancel permission edit','POST','LOCAL navigate','Keep the existing source grant policy unchanged.',{visibility:'editPost mode only',clearMode:true});
 // Request fulfillment returns a confirmed reference to the request; it never enters post publication.
 for(const s of screens)for(const a of s.actions)if(a.target==='RECIPE_REQUEST')change(s.id,a.label,{mode:'requester'});
 add('THREAD','Review incoming recipe request','RECIPE_REQUEST','LOCAL navigate','Author context: resolve the authorized pending recipeRequestId and retain the thread return destination.',{mode:'fulfillRequest',responseConfirmed:false});
 change('RECIPE_REQUEST','Answer with a recipe',{visibility:'fulfillRequest mode only',mode:'fulfillRequest',responseConfirmed:false,effect:'Select a redistributable existing recipe for this private request. Preserve recipeRequestId and return from attachment review before an explicit response is sent.'});
 change('RECIPE_REQUEST','Send request',{visibility:'requester mode only'});
 change('RECIPE_REQUEST','Cancel my request',{visibility:'requester mode only',clearMode:true});
 change('RECIPE_REQUEST','Cancel',{visibility:'requester mode only',clearMode:true});
 add('RECIPE_REQUEST','Back to conversation','THREAD','LOCAL navigate','Preserve the pending request and discard only the unsubmitted response selection; send nothing.',{visibility:'fulfillRequest mode only',clearMode:true});
 add('REVIEW_ATTACHMENT','Use this recipe in my response','RECIPE_REQUEST','LOCAL update','Confirm the selected eligible recipeVersionId and its rights for this request. Return a typed response selection without saving a post draft, widening an audience, or sending a response.',{visibility:'fulfillRequest mode only',mode:'fulfillRequest',responseConfirmed:true,validate:true});
 add('REVIEW_ATTACHMENT','Cancel recipe response selection','RECIPE_REQUEST','LOCAL navigate','Return to the request with no confirmed response and no publication changes.',{visibility:'fulfillRequest mode only',mode:'fulfillRequest',responseConfirmed:false});
 // A recipe may be inspected inside a picker without losing its typed return route.
 add('RECIPE','Select this recipe',null,'LOCAL pickerResult','Picker-only selection validates applicable source rights and returns the chosen reference to the requesting draft.',{visibility:'picker mode only',branches:[['REVIEW_ATTACHMENT','attachment or request response'],['SOS_DETAIL','SOS suggestion'],['PACT_CREATE','pact source'],['POLL_CREATE','poll choice']]});
 add('RECIPE','Cancel selection',null,'LOCAL pickerCancel','Return to picker.cancelTo with the original source selection unchanged.',{visibility:'picker mode only',branches:[['ATTACH_RECIPE','attachment or response'],['SOS_DETAIL','SOS suggestion'],['PACT_CREATE','pact source'],['POLL_CREATE','poll choice']]});
 add('CIRCLES','Cancel selection',null,'LOCAL pickerCancel','Return to picker.cancelTo without changing the event circle.',{visibility:'picker mode only',branches:[['SOS_CREATE','SOS audience'],['POTLUCK_CREATE','shared meal audience'],['AUDIENCE','post audience']]});
 add('RECIPE','Share a shortcut','SHORTCUT_CREATE','LOCAL navigate','Retain the authorized recipeVersionId and begin a separate original-text tip draft; unreviewed tips never alter reviewed steps.',{mode:'draft'});
 add('RECIPE','View a shared shortcut','SHORTCUT_DETAIL','LOCAL navigate','Open an explicitly selected accessible shortcut reference for this recipe. If no tip is available, show the detail empty state rather than inventing a shortcut ID.');
 byId('AUDIENCE').fields.find(f=>f.id==='audience').options=['Only you','My kitchen circle','Selected invited circles'];
 byId('AUDIENCE').fields.find(f=>f.id==='audience').example='Only you';
 byId('SAVE_PERMISSION').fields.find(f=>f.id==='allow').example='false';
 for(const id of ['AUTH_SIGNUP','REVIEW_ATTACHMENT','DELETE_POST'])for(const f of byId(id).fields)if(f.type==='checkbox')f.example='false';
 byId('DELETE_ACCOUNT').fields[0].example='';
 byId('ATTACH_RECIPE').fields.push({id:'steps',label:'My preparation steps (for entered recipes)',type:'textarea',example:'',required:false,options:[]},{id:'servings',label:'Recipe servings',type:'number',example:'1',required:false,options:[]},{id:'time',label:'My estimated total minutes',type:'number',example:'10',required:false,options:[]},{id:'activeMinutes',label:'My estimated active minutes',type:'number',example:'5',required:false,options:[]},{id:'equipment',label:'Equipment used',type:'text',example:'Bowl',required:false,options:[]});
 byId('POST').fields.push({id:'caption',label:'Edit my caption (owner only)',type:'textarea',example:'Made this work.',required:false,options:[]});
 byId('PACT_DETAIL').fields.push({id:'schedule',label:'Proposed meal time',type:'datetime-local',example:'2026-09-14T19:30',required:false,options:[]});
 byId('SOS_DETAIL').fields.push({id:'ingredients',label:'My corrected shared ingredients',type:'text',example:'Bread, yogurt, cucumber',required:false,options:[]},{id:'minutes',label:'My updated available minutes',type:'number',example:'10',required:false,options:[]});
 change('PACT_DETAIL','Share a progress photo',{effect:'Open an optional composer with self-only initial audience. Select a supported self/circle audience explicitly; pact participation does not authorize a new audience type.'});
 change('AUTH_CALLBACK','Complete provider callback',{effect:'Validate pending state and exact redirect; exchange the code with the protected PKCE verifier. Then validate returned ID-token issuer, audience and nonce, bootstrap the account and securely save Bootstrap.sessionId for X-Device-Session on member calls.'});
 change('PUBLISH_STATUS','Share with chosen circle',{label:'Publish to selected audience'});
 change('ATTACH_RECIPE','Review entered details',{visibility:'publishing mode only'});
 // Hydration contracts: every screen declares what is local, server-read or provider state.
 const loads={
  AUTH_WELCOME:['GET /v1/config'],AUTH_SIGNUP:['GET /v1/config'],AUTH_LOGIN:['GET /v1/config'],AUTH_VERIFY:['EXTERNAL Cognito.pendingChallenge'],AUTH_RESET:['LOCAL recoveryState'],AUTH_RESET_CONFIRM:['LOCAL recoveryState'],AUTH_CALLBACK:['EXTERNAL OIDC.callbackState'],LEGAL:['GET /v1/config'],PROFILE_SETUP:['GET /v1/me'],FOOD_PREFS:['GET /v1/preferences'],EQUIPMENT:['GET /v1/preferences'],NOTIFICATION_PERMISSION:['EXTERNAL OS.notificationAuthorization'],INVITE_ACCEPT:['GET /v1/invitations/preview'],
  HOME:['GET /v1/preferences','GET /v1/memories','GET /v1/recipes'],REQUEST:['GET /v1/preferences','GET /v1/pantry/items'],PANTRY:['GET /v1/pantry/items','GET /v1/ingredients'],EFFORT:['GET /v1/preferences','LOCAL requestDraft'],TASTE:['LOCAL requestDraft'],RECOMMENDATIONS:['GET /v1/plans/{planId}'],RECIPE:['GET /v1/recipes/{recipeId}/versions/{recipeVersionId}'],ADAPT:['GET /v1/plans/{planId}','GET /v1/preferences','GET /v1/pantry/items'],VARIANT:['GET /v1/plans/{planId}','GET /v1/plans/{planId}/explanation'],COOK:['GET /v1/cook-sessions/{sessionId}','LOCAL pinnedRecipeBundle'],TIMER:['LOCAL timerDeadline'],MEAL_DONE:['GET /v1/cook-sessions/{sessionId}'],FEEDBACK:['LOCAL sessionFeedbackDraft'],REUSE:['LOCAL completedMealContext'],
  COOKBOOK:['GET /v1/saved-recipes','GET /v1/collections'],COLLECTION:['GET /v1/collections/{collectionId}'],COLLECTION_EDIT:['GET /v1/collections/{collectionId}','GET /v1/entitlements'],MEMORY:['GET /v1/memories'],MEMORY_DETAIL:['GET /v1/memories/{memoryId}'],TODAY:['GET /v1/posts/today'],STORY:['GET /v1/posts/{postId}','POST /v1/media/{mediaId}/access'],PROFILE_PLATE:['GET /v1/profiles/{userId}/plate'],POST:['GET /v1/posts/{postId}','POST /v1/media/{mediaId}/access'],CAPTURE:['LOCAL draftContext'],EDIT_MEDIA:['GET /v1/post-drafts/{draftId}','LOCAL mediaDraft'],ATTACH_RECIPE:['LOCAL recipePickerContext'],REVIEW_ATTACHMENT:['GET /v1/post-drafts/{draftId}','LOCAL confirmedAttachment'],AUDIENCE:['GET /v1/circles','LOCAL composerOrPostMode'],SAVE_PERMISSION:['LOCAL exactRecipeGrantContext'],PUBLISH_STATUS:['GET /v1/post-drafts/{draftId}','GET /v1/media/{mediaId}'],REMIX_TRAIL:['GET /v1/posts/{postId}/remixes'],
  CIRCLES:['GET /v1/circles'],CIRCLE:['GET /v1/circles/{circleId}'],CIRCLE_CREATE:['LOCAL circleDraft'],CIRCLE_MEMBERS:['GET /v1/circles/{circleId}/members'],INVITE:['GET /v1/circles/{circleId}'],INBOX:['GET /v1/threads','GET /v1/notifications'],THREAD:['GET /v1/threads/{threadId}','GET /v1/threads/{threadId}/messages'],RECIPE_REQUEST:['GET /v1/posts/{postId}'],SETTINGS:['GET /v1/me'],PRIVACY:['GET /v1/me','GET /v1/preferences'],BLOCKED:['GET /v1/blocks'],REPORT:['LOCAL authorizedTargetReference'],DELETE_POST:['GET /v1/posts/{postId}'],DELETE_ACCOUNT:['GET /v1/me','GET /v1/entitlements'],NOTIFICATIONS:['GET /v1/notification-settings','EXTERNAL OS.notificationAuthorization'],SESSIONS:['GET /v1/account/sessions'],
  SOS_CREATE:['GET /v1/circles','LOCAL voluntaryIngredientSubset'],SOS_DETAIL:['GET /v1/sos/{sosId}'],TONIGHT:['GET /v1/saved-recipes/{savedRecipeId}','GET /v1/preferences','GET /v1/pantry/items'],PACT_CREATE:['LOCAL selectedShareableRecipe'],PACT_DETAIL:['GET /v1/pacts/{pactId}'],POTLUCK_CREATE:['GET /v1/circles'],POTLUCK_DETAIL:['GET /v1/potlucks/{potluckId}','GET /v1/potlucks/{potluckId}/contributions'],CONTRIBUTION:['GET /v1/potlucks/{potluckId}'],SHORTCUT_CREATE:['LOCAL recipeVersionContext'],SHORTCUT_DETAIL:['GET /v1/shortcuts/{shortcutId}'],POLL_CREATE:['GET /v1/circles','LOCAL selectedOptions'],POLL_DETAIL:['GET /v1/polls/{pollId}'],VIDEO_EDIT:['LOCAL videoDraft'],
  HOUSEHOLDS:['GET /v1/households','GET /v1/entitlements'],HOUSEHOLD_MEMBER:['GET /v1/households/{householdId}/members'],HOUSEHOLD_PREFS:['GET /v1/households/{householdId}/preferences'],PACK_STORE:['GET /v1/packs','EXTERNAL RevenueCat.offerings'],PACK_DETAIL:['GET /v1/packs/{packId}','GET /v1/entitlements'],PAYWALL:['EXTERNAL RevenueCat.offerings','GET /v1/entitlements'],PURCHASE_STATUS:['GET /v1/entitlements'],MANAGE_PLAN:['GET /v1/entitlements'],EXPORT:['GET /v1/jobs/{jobId}'],SUPPORT:['LOCAL supportReceipt'],UNAVAILABLE:['LOCAL typedFailure'],OFFLINE:['LOCAL eligiblePrivateCache'],
  ADMIN_LOGIN:['EXTERNAL WorkforceOIDC.configuration'],ADMIN_HOME:['GET /v1/admin/health'],ADMIN_RECIPE:['GET /v1/admin/recipes'],ADMIN_REVIEW:['GET /v1/admin/recipes/{recipeId}/versions/{recipeVersionId}'],ADMIN_SUBSTITUTION:['GET /v1/admin/substitutions'],ADMIN_REPORTS:['GET /v1/admin/reports'],ADMIN_CASE:['GET /v1/admin/cases/{caseId}'],ADMIN_AUDIT:['GET /v1/admin/audit'],ADMIN_FLAGS:['GET /v1/admin/flags'],ADMIN_PACK:['GET /v1/admin/packs'],ADMIN_INCIDENT:['GET /v1/admin/health','GET /v1/admin/incidents'],PEOPLE_PICKER:['GET /v1/circles/{circleId}/members'],CONFIRM_ACTION:['LOCAL pendingCommand']
 };
 for(const s of screens){s.loads=loads[s.id]||['LOCAL inheritedContext'];s.contextRules='Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.';}
 byId('PRIVACY').loads=['GET /v1/me/privacy','GET /v1/preferences'];
 byId('RECIPE_REQUEST').loads.push('GET /v1/recipe-requests/{recipeRequestId}');
 byId('REVIEW_ATTACHMENT').contextRules='Resolve exactly one mode: draft reads the owned post draft; editPost reads the owned post and selected recipe version; fulfillRequest reads the authorized recipe request and selected recipe version. Skip draft hydration entirely in fulfillRequest/editPost. Confirmation creates only the matching typed draft, post edit or private response selection; it never switches those resource scopes.';
 byId('ATTACH_RECIPE').contextRules='Existing-source mode requires an eligible authorized recipeVersionId. Manual community-recipe mode requires canonical ingredient quantities/units, structured steps, servings, equipment, active/total effort and source-rights attestation before review; visible entry labels are serialized with Attachment.personalRecipe schema, never directly as form state. A recipe request response accepts only an existing permitted recipe reference; manual details cannot be smuggled into its strict DTO.';
 byId('ADMIN_LOGIN').actions.find(a=>a.label==='Sign in with staff SSO').auth='public';
 byId('ADAPT').contextRules='If opened from a social/catalog recipe with no planId, retain authorized source reference and collect constraints. The Make my version command first creates a source-bound plan via POST /v1/plans, then calls adaptations with the returned planId (or consumes the already adapted response). Do not GET an invented plan ID.';
 byId('THREAD').contextRules='Opening Reply from a post creates/finds a scoped private thread via POST /v1/threads before message reads. Coordination threads use server-provided IDs and participant membership.';
 byId('PUBLISH_STATUS').contextRules='Unpublished draft starts review mode. Upload pipeline: POST /v1/media → signed native upload → POST /v1/media/{mediaId}/complete → GET status with bounded backoff → explicit POST /v1/posts. No upload or status callback auto-publishes.';
 byId('RECIPE').contextRules='Recipe context may be a licensed catalog revision, immutable owned save or reviewed plan variant. Read the matching owner/rights endpoint; in picker mode return the selected reference instead of starting cooking. Third-party private copies do not imply redistribution rights.';
 byId('HOME').contextRules='After identity callback first POST /v1/account/bootstrap; guest identity uses POST /v1/guest-sessions. Guest merge requires consent and POST /v1/guest-sessions/{guestSessionId}/merge. Resolve safe deferred links after onboarding; do not auto-accept invitations.';
}

# FeedMe — actual retained Android meal host

14 September 2026. **VERIFIED BOUNDED COMPONENT.** The previous six native presentation tests used synthetic view-state models. This component adds actual `FeedMeMealFlow` / `MealFlowExperience` / controller / native encrypted-store integration; account verification and transport remain explicitly synthetic.

## Lifetime and navigation

The instrumentation-only session uses public Android credential/data/control/work factories, a retained composition reservation and runtime-issued `PrivateSessionAccess`. It never constructs private access with reflection or adds a permissive production test factory. Activity recreation borrows the same experience and session; it does not close or recreate native owners. Actual disposal closes controllers before exact acknowledged test-session retirement and native/root closes.

Nine native methods cover dirty partial text and dialog dismissal/discard; exact local Save and new-owner restore; Plan/recipe/system-Back navigation across recreation; original-key/body replay after unknown outcome; synchronous invalidation and late reply redaction; recreation cancellation with retained command; dirty recommendation Save returning to the originally requested draft; and real pantry/preference child-page actions with native persistence and planning guard.

An independent review caught a real host defect: saving a dirty recommendation moves the controller to REQUEST, so reading the post-save screen incorrectly exited the entire journey. Back now retains its original destination with the exact dialog intent. A native regression covers this case. Native testing also exposed incomplete numeric text classified as storage failure; validation now rejects it narrowly before I/O, preserving the strict INVALID_DATA assertion.

## Isolation and truthful cleanup

The fixture reserves before native I/O and remains retained after failed setup/cleanup. Its namespace is limited to the isolated `com.feedme.app.test` UID and UUID-owned `meal-host-native-` directory. Unknown aliases/files are preserved. Android's platform-owned no_backup directory may be0771; only that parent is treated separately. Fixture directories remain0700, files0600, with no links or lock-file second-descriptor reads. No broad chmod, key sweep or directory deletion is authorized.

Only successful exact runtime retirement and native close acknowledgements permit removal of this fixture's verified original remaining aliases/files. Root invalidation cleanup may explicitly reopen the same previously successful fixture, synthetically verify restore and retire it; incomplete initial setup does not gain cleanup authority.

## Evidence and limits

The [frozen fourth-batch run](PARALLEL_KITCHEN_SOCIAL_HOST.md) passed at2026-09-14T00:45:13.550Z. All nine exact native host methods passed with six retained PNGs and clean owned-fixture inventory; all six earlier presentation methods also pass. Source-bound combined acceptance includes129 mealflow/57 app JVM methods, all2,117 Kotlin/server/database regressions,181 Node checks and345 native successes. Seven separately witnessed interrupted starts belong to session recovery, not these Activity-recreation tests.

Focused attempts are retained under `docs/verification/meal-host/attempts`. First six-method attempt refused platform-parent mode before native creation. Second seven-method attempt passed six, finding the numeric validation classification bug. The first nine-method run passed six, exposing a missing required field in the synthetic pantry response and UI automation timing/label issues. Corrections preserve strict assertions. The next run at `2026-09-14T00:21:36.867Z` passes all nine methods and retains six screenshots with exact fixture cleanup; it is focused evidence, not combined final-source acceptance. [Current batch status](PARALLEL_KITCHEN_SOCIAL_HOST.md).

This is not real provider login, a client/server HTTP journey, process death, an actual delayed storage acknowledgement/new-edit race, physical power loss, reviewed recipe permission, cooking/save/share, iOS or release acceptance. Existing common form-generation tests cover delayed acknowledgement models, not that missing native fault scenario. Historical demo/public APK bytes remain unchanged.

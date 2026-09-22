package com.feedme.core

/** Connected-account product policy, not verified age, identity evidence or a provider grant.
 * Released offline artifacts and historical consent are not reclassified by these constants. */
object FeedMeAdultPolicy {
    const val MINIMUM_AGE_YEARS = 18
    const val AGE_DECLARATION = "I confirm that I am 18 years old or older."
    const val ELIGIBILITY_POLICY_VERSION = "feedme-adults-18-self-attested-v1"
    const val TERMS_VERSION = "feedme-connected-adults-2026-09-20-v1"
    /** Existing canonical literal; meaningful as this declaration only with both exact versions. */
    const val BOOTSTRAP_DECLARATION = "adultPilot"
}

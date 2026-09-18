package com.feedme.server.runtime

/** None establishes whole-V1 production/release readiness. The default container fails;
 * local liveness only proves a responding listener. The explicitly configured core mode
 * checks its actual dependencies but retains the canonical degraded status, since other
 * V1 routes and first-user eligibility/reauthentication gates remain separate. */
enum class ServiceHealthMode {
    LOCAL_LIVENESS,
    UNCONFIGURED_READINESS,
    ACCOUNT_CORE_DEPENDENCIES,
}

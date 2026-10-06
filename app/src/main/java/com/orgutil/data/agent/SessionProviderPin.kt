package com.orgutil.data.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pins which chat session the shared LlmClient router is serving. The app
 * runs one agent run at a time; the ViewModel sets this whenever the
 * active session changes so provider resolution (session profile override
 * -> default profile) uses the right row.
 */
@Singleton
class SessionProviderPin @Inject constructor() {
    @Volatile
    var sessionId: String? = null
}

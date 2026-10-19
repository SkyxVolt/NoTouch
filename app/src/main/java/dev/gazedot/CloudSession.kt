package dev.gazedot

/** Session-only development settings. The OpenAI key exists only on the server. */
object CloudSession {
    @Volatile var serverUrl = ""
        private set
    @Volatile var serverToken = ""
        private set
    @Volatile var poseEnabled = false
        private set
    @Volatile var contextEnabled = false
        private set
    @Volatile var automatic = false
        private set
    @Volatile var generation = 0L
        private set
    @Volatile var requested = false
    @Volatile var calls = 0
    @Volatile var tokens = 0
    @Volatile var status = "GPT 실험 꺼짐"
    val enabled get() = serverToken.isNotBlank() && (poseEnabled || contextEnabled)
    const val MAX_CALLS = 20
    @Synchronized fun configure(url: String, key: String, pose: Boolean, context: Boolean, auto: Boolean) {
        val endpoint=try { java.net.URI(url.trim()) } catch(e: java.net.URISyntaxException) { throw IllegalArgumentException("HTTPS 서버 주소를 확인하세요") }
        require(endpoint.scheme=="https" && !endpoint.host.isNullOrBlank() && endpoint.rawUserInfo==null &&
            endpoint.rawQuery==null && endpoint.rawFragment==null && (endpoint.path.isNullOrEmpty() || endpoint.path=="/")) { "HTTPS 서버 기본 주소를 입력하세요" }
        require(key.trim().matches(Regex("[A-Za-z0-9_-]{43}"))) { "NoTouch 서버 접속 토큰을 입력하세요 (OpenAI 키 아님)" }
        generation++; serverUrl=url.trim().trimEnd('/'); serverToken=key.trim(); poseEnabled=pose; contextEnabled=context; automatic=auto
        requested=false; status="GPT-5.4 설정 적용 · 최대 20회 API 호출/프로세스"
    }
    @Synchronized fun disable() {
        generation++; serverToken=""; poseEnabled=false; contextEnabled=false; automatic=false
        requested=false; status="GPT 실험 꺼짐 · 서버 접속 토큰 제거됨"
    }
    @Synchronized fun reserveCall(): Boolean {
        if(calls>=MAX_CALLS) return false
        calls++; return true
    }
    fun pauseAutomatic() { automatic=false; requested=false }
}

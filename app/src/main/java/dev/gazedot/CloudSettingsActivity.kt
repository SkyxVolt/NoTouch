package dev.gazedot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity

class CloudSettingsActivity : ComponentActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val tick=object : Runnable {
        override fun run() {
            status.text="${CloudSession.status}\nAPI 호출 ${CloudSession.calls}/${CloudSession.MAX_CALLS} · 누적 토큰 ${CloudSession.tokens}"
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,40,30,30) }
        fun text(s: String)=TextView(this).apply { text=s; textSize=16f; setPadding(0,12,0,12); root.addView(this) }
        fun button(s: String, action: ()->Unit) { root.addView(Button(this).apply { text=s; setOnClickListener { action() } }) }
        text("GPT-5.4 실험 · 두 기능 독립 비교")
        text("1번: 전면 카메라 얼굴·눈 사진과 머리/홍채 수치로 실험용 시선 샘플을 요청합니다. 이 결과는 빨간 포인터를 이동시키지 않습니다.\n2번: 시선 좌표와 현재 앱의 버튼 이름·위치로 클릭 후보를 추천합니다. 파란 원이 후보이며 긴 깜빡임으로 고정·확정합니다.")
        text("활성화하면 해당 정보가 NoTouch 서버를 거쳐 OpenAI API로 전송되고 별도 API 요금이 발생합니다. 입력칸 내용·비밀번호·화면 캡처는 보내지 않습니다. OpenAI 키는 서버에만 보관합니다. 여기는 별도의 NoTouch 접속 토큰을 입력하며 추적 종료 시 메모리에서 제거합니다.")
        val address=EditText(this).apply {
            hint="서버 주소 https://your-project.vercel.app"; setSingleLine()
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(CloudSession.serverUrl.ifBlank { getPreferences(MODE_PRIVATE).getString("server_url", "") ?: "" })
        }; root.addView(address)
        val key=EditText(this).apply {
            hint=if(CloudSession.serverToken.isBlank()) "NoTouch 서버 접속 토큰" else "새 접속 토큰 (비우면 유지)"
            setSingleLine()
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
            isSaveEnabled=false; importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }; root.addView(key)
        val pose=CheckBox(this).apply { text="1. GPT-5.4 눈·머리 자세 분석"; isChecked=CloudSession.poseEnabled }; root.addView(pose)
        val context=CheckBox(this).apply { text="2. GPT-5.4 화면 후보 보정"; isChecked=CloudSession.contextEnabled }; root.addView(context)
        val auto=CheckBox(this).apply { text="10초 이상 간격으로 자동 분석 (한 번에 한 요청 흐름)"; isChecked=CloudSession.automatic }; root.addView(auto)
        val consent=CheckBox(this).apply { text="선택한 데이터 전송과 API 비용을 확인했습니다" }; root.addView(consent)
        status=text(CloudSession.status)
        button("설정 적용") {
            val value=key.text.toString().trim().ifBlank { CloudSession.serverToken }
            if((pose.isChecked || context.isChecked) && (value.isBlank() || !consent.isChecked)) {
                Toast.makeText(this,"서버 접속 토큰과 전송 확인이 필요합니다",Toast.LENGTH_LONG).show()
            } else {
                if(!pose.isChecked && !context.isChecked) CloudSession.disable()
                else {
                    try {
                        CloudSession.configure(address.text.toString(),value,pose.isChecked,context.isChecked,auto.isChecked)
                        getPreferences(MODE_PRIVATE).edit().putString("server_url",CloudSession.serverUrl).apply()
                    } catch(e: IllegalArgumentException) {
                        Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()
                        return@button
                    }
                }
                key.text.clear(); TrackerState.cancelActions?.invoke()
            }
        }
        button("현재 프레임 1회 분석") {
            if(!TrackerState.running || !CloudSession.enabled) Toast.makeText(this,"카메라 시작 후 GPT 설정을 적용하세요",Toast.LENGTH_LONG).show()
            else { CloudSession.requested=true; CloudSession.status="다음 눈 검출 프레임 분석 대기" }
        }
        button("GPT 끄기 · 접속 토큰 제거") {
            CloudSession.disable(); TrackerState.cancelActions?.invoke(); key.text.clear()
            pose.isChecked=false; context.isChecked=false; auto.isChecked=false; consent.isChecked=false
        }
        text("정확한 홍채 각도/거리 측정 모델이 아닙니다. 모델의 신뢰도는 실측 정확도가 아닙니다. 촬영 후 8초가 지났거나 눈·머리 자세가 달라지면 결과를 조작에 적용하지 않습니다. 기존 로컬 모델이 있으면 계속 사용할 수 있습니다. 앱의 20회 한도 외에도 서버는 UTC 날짜 기준 전체 200회/일, 분당 12회로 제한합니다. 서버 한도는 앱을 재시작해도 유지됩니다.")
        button("돌아가기") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() {
        super.onResume(); TrackerState.interactionAllowed=false; TrackerState.cancelActions?.invoke(); handler.post(tick)
    }
    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onStart() { super.onStart(); TrackerState.settingsStarted() }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }
}

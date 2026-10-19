package dev.gazedot

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity

/** Safe local place to verify click, scrolling and dictation before other apps. */
class PracticeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d=resources.displayMetrics.density
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding((24*d).toInt(),(100*d).toInt(),(24*d).toInt(),(48*d).toInt()) }
        val title=TextView(this).apply { text="눈 조작 연습\n앱에서 조작을 켠 뒤 테스트하세요"; textSize=23f }; root.addView(title)
        var count=0
        root.addView(Button(this).apply { text="클릭 횟수: 0"; setOnClickListener { text="클릭 횟수: ${++count}" } })
        root.addView(EditText(this).apply { hint="이 입력칸을 선택하고 빠르게 두 번 깜빡인 뒤 말하세요"; minLines=3; setSingleLine(false) })
        repeat(24) { n -> root.addView(TextView(this).apply { text="스크롤 연습 ${n+1}\n화면 밖 위·아래를 바라보세요"; textSize=18f; setPadding(0,35,0,35) }) }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume(); TrackerState.interactionAllowed=true }
}

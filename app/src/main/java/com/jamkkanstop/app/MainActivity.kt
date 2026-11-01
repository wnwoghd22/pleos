package com.jamkkanstop.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jamkkanstop.app.data.OpenHours
import com.jamkkanstop.app.data.Toilet
import com.jamkkanstop.app.data.ToiletType
import com.jamkkanstop.app.pleos.ComfortMode
import com.jamkkanstop.app.pleos.LocationSource
import com.jamkkanstop.app.urgent.ComfortStatus
import com.jamkkanstop.app.pleos.StopInsertMode
import com.jamkkanstop.app.pleos.StopInsertResult
import com.jamkkanstop.app.urgent.Announcements
import com.jamkkanstop.app.urgent.StopCandidate
import com.jamkkanstop.app.urgent.StopPlan
import com.jamkkanstop.app.urgent.UrgentStopViewModel
import com.jamkkanstop.app.urgent.UrgentUiState

class MainActivity : ComponentActivity() {

    private val viewModel: UrgentStopViewModel by viewModels()

    // 거부해도 앱은 동작한다 (내비 위치로 대신함).
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, LocationSource.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(LocationSource.PERMISSION)
        }
        setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UrgentScreen(
                        state = state,
                        onUrgent = viewModel::onUrgent,
                        onSwitchToBackup = viewModel::onSwitchToBackup,
                        onDismiss = viewModel::onDismiss,
                        onComfort = viewModel::onComfort,
                        onComfortOff = viewModel::onComfortOff,
                    )
                }
            }
        }
    }
}

// 주행 중 1~2초 안에 읽히도록: 큰 글자, 한 화면에 결론 하나, 버튼은 최대 두 개.

@Composable
fun UrgentScreen(
    state: UrgentUiState,
    onUrgent: () -> Unit,
    onSwitchToBackup: () -> Unit,
    onDismiss: () -> Unit,
    onComfort: (ComfortMode) -> Unit = {},
    onComfortOff: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            UrgentUiState.Idle -> UrgentButton(onUrgent)
            UrgentUiState.Searching -> BigMessage("가까운 화장실 찾는 중…")
            is UrgentUiState.Guiding -> {
                GuidingPanel(state.plan, state.insert, onSwitchToBackup, onDismiss)
                Spacer(Modifier.height(32.dp))
                ComfortRow(state.comfort, onComfort, onComfortOff)
            }
            is UrgentUiState.Failed -> {
                BigMessage(state.message)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LargeButton("다시 찾기", onUrgent)
                    LargeOutlinedButton("닫기", onDismiss)
                }
            }
        }
    }
}

@Composable
private fun UrgentButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.width(560.dp).height(200.dp)) {
        Text("화장실 급해요", fontSize = 48.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuidingPanel(
    plan: StopPlan,
    insert: StopInsertResult,
    onSwitchToBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val best = plan.best
    Text(
        text = "${Announcements.minutes(best.etaSeconds)}분",
        fontSize = 120.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(best.toilet.name, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
    Text(detailLine(best), fontSize = 24.sp, color = Color.LightGray)
    Spacer(Modifier.height(12.dp))
    Text(routeLine(insert), fontSize = 22.sp, color = if (insert.confirmed) Color(0xFF8BD18B) else Color(0xFFFFC46B))

    Spacer(Modifier.height(40.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        plan.backup?.let { backup ->
            LargeOutlinedButton("예비: ${backup.toilet.name} ${Announcements.minutes(backup.etaSeconds)}분", onSwitchToBackup)
        }
        LargeOutlinedButton("닫기", onDismiss)
    }
}

/** 가는 동안 버티게 돕기. 고르기 전에는 두 모드 버튼, 켠 뒤에는 무엇을 바꿨는지와 끄기 버튼. */
@Composable
private fun ComfortRow(
    status: ComfortStatus,
    onComfort: (ComfortMode) -> Unit,
    onComfortOff: () -> Unit,
) {
    when (status) {
        ComfortStatus.Offer -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LargeButton("배 아파요 · 따뜻하게") { onComfort(ComfortMode.WARM) }
            LargeButton("식은땀 나요 · 시원하게") { onComfort(ComfortMode.COOL) }
        }
        is ComfortStatus.Applying -> Text("차 안을 맞추는 중…", fontSize = 26.sp, color = Color.LightGray)
        is ComfortStatus.Active -> Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Announcements.comfortSummary(status.mode, status.changes) ?: "바꿀 수 있는 설정이 없어요",
                fontSize = 26.sp,
                color = Color(0xFF8FD3F4),
            )
            LargeOutlinedButton("원래대로", onComfortOff)
        }
    }
}

private fun detailLine(c: StopCandidate): String = buildList {
    add(
        when (c.toilet.type) {
            ToiletType.REST_AREA -> "휴게소"
            ToiletType.GAS_STATION -> "주유소"
            ToiletType.PUBLIC -> "공중화장실"
        },
    )
    when (c.openStatus) {
        OpenHours.Status.OPEN -> add("지금 개방")
        OpenHours.Status.UNKNOWN -> add("개방시간 미확인")
        OpenHours.Status.CLOSED -> Unit
    }
    if (c.etaEstimated) add("시간은 거리로 추정")
}.joinToString(" · ")

private fun routeLine(insert: StopInsertResult): String = when {
    !insert.confirmed -> "내비 반영 확인 안 됨"
    insert.mode == StopInsertMode.AS_DESTINATION -> "목적지로 안내 중"
    insert.mode == StopInsertMode.REBUILT_ROUTE_DROPPED_SECOND -> "경유지로 추가 (두 번째 경유지 제외)"
    else -> "경유지로 추가 · 원래 목적지 유지"
}

@Composable
private fun BigMessage(text: String) {
    Text(text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LargeButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.height(96.dp)) {
        Text(text, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun LargeOutlinedButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(96.dp)) {
        Text(text, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

// ---- Preview ----

private val previewPlan = StopPlan(
    best = StopCandidate(
        toilet = Toilet("1", "만남의광장 휴게소", "서울 서초구", 37.46, 127.04, ToiletType.REST_AREA, "24시간"),
        etaSeconds = 240, etaEstimated = false, straightMeters = 2100.0, openStatus = OpenHours.Status.OPEN,
    ),
    backup = StopCandidate(
        toilet = Toilet("2", "양재역 공중화장실", "서울 서초구", 37.48, 127.03, ToiletType.PUBLIC, null),
        etaSeconds = 360, etaEstimated = true, straightMeters = 2900.0, openStatus = OpenHours.Status.UNKNOWN,
    ),
)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun GuidingPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            UrgentScreen(
                state = UrgentUiState.Guiding(previewPlan, StopInsertResult(StopInsertMode.AS_WAYPOINT, true)),
                onUrgent = {}, onSwitchToBackup = {}, onDismiss = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun IdlePreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface { UrgentScreen(UrgentUiState.Idle, {}, {}, {}) }
    }
}

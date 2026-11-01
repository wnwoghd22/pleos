package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.data.Toilet
import com.jamkkanstop.app.data.ToiletRepository
import com.jamkkanstop.app.data.ToiletType
import com.jamkkanstop.app.pleos.NaviGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class UrgentStopPlannerTest {

    // 기준점: 서울 시청 근처
    private val hereLat = 37.5665
    private val hereLng = 126.9780
    private val noon = LocalTime.NOON

    private fun toilet(id: String, latOffset: Double, type: ToiletType = ToiletType.PUBLIC, open: String? = "24시간") =
        Toilet(id, id, "", hereLat + latOffset, hereLng, type, open)

    @Test fun `직선거리가 멀어도 내비 ETA가 짧은 곳을 고른다`() = runBlocking {
        val near = toilet("near", 0.005)   // 약 0.6km
        val far = toilet("far", 0.010)     // 약 1.1km
        val eta = EtaSource { if (it.id == "near") 600 else 180 } // 가까운 곳이 일방통행 등으로 10분
        val plan = UrgentStopPlanner(ToiletRepository(listOf(near, far)), eta).plan(hereLat, hereLng, noon)!!

        assertEquals("far", plan.best.toilet.id)
        assertEquals("near", plan.backup?.toilet?.id)
    }

    @Test fun `ETA가 비슷하면 휴게소·주유소를 우선한다`() = runBlocking {
        val publicToilet = toilet("public", 0.005)
        val restArea = toilet("rest", 0.006, ToiletType.REST_AREA)
        val eta = EtaSource { if (it.id == "public") 200 else 230 } // 같은 '3분대'
        val plan = UrgentStopPlanner(ToiletRepository(listOf(publicToilet, restArea)), eta).plan(hereLat, hereLng, noon)!!

        assertEquals("rest", plan.best.toilet.id)
    }

    @Test fun `닫힌 화장실은 제외한다`() = runBlocking {
        val closed = toilet("closed", 0.001, open = "09:00~11:00")
        val open = toilet("open", 0.009)
        val plan = UrgentStopPlanner(ToiletRepository(listOf(closed, open)), { null }).plan(hereLat, hereLng, noon)!!

        assertEquals("open", plan.best.toilet.id)
        assertNull(plan.backup)
    }

    @Test fun `ETA를 못 받으면 거리로 추정하고 표시한다`() = runBlocking {
        val plan = UrgentStopPlanner(ToiletRepository(listOf(toilet("a", 0.01))), { null }).plan(hereLat, hereLng, noon)!!

        assertTrue(plan.best.etaEstimated)
        assertTrue(plan.best.etaSeconds in 100..300) // 1.1km × 1.3 ÷ 30km/h ≈ 170초
    }

    @Test fun `거리 추정 후보는 내비가 잰 후보를 이기지 못한다`() = runBlocking {
        // 가까운 3곳만 내비에 묻는다. 4번째는 추정치(짧게 나옴)라도 뒤로 간다.
        val toilets = listOf(toilet("a", 0.001), toilet("b", 0.002), toilet("c", 0.003), toilet("d", 0.004))
        val eta = EtaSource { 1200 } // 내비 기준 20분 (정체)
        val plan = UrgentStopPlanner(ToiletRepository(toilets), eta).plan(hereLat, hereLng, noon)!!

        assertTrue(!plan.best.etaEstimated)
        assertTrue(plan.backup!!.toilet.id != "d")
    }

    @Test fun `내비가 한 번 응답하지 않으면 나머지 후보는 묻지 않는다`() = runBlocking {
        var asked = 0
        val eta = EtaSource { asked++; null }
        val toilets = listOf(toilet("a", 0.001), toilet("b", 0.002), toilet("c", 0.003))
        val plan = UrgentStopPlanner(ToiletRepository(toilets), eta).plan(hereLat, hereLng, noon)!!

        assertEquals(1, asked)
        assertTrue(plan.best.etaEstimated)
    }

    @Test fun `20km 안에 없으면 null`() = runBlocking {
        val plan = UrgentStopPlanner(ToiletRepository(listOf(toilet("far", 0.5))), { null }).plan(hereLat, hereLng, noon)
        assertNull(plan)
    }

    @Test fun `ETA 원시값 정규화`() {
        val now = 1_800_000_000_000L
        assertEquals(240, NaviGateway.normalizeEtaSeconds(240, now))
        assertEquals(300, NaviGateway.normalizeEtaSeconds(1_800_000_300, now)) // 도착 시각(epoch 초)
        assertNull(NaviGateway.normalizeEtaSeconds(0, now))
    }
}

package kr.joonlab.foldlab.input

import android.Manifest
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 받아쓰기 배선 검증.
 *
 * 말은 자동으로 못 낸다 — **실제 전사 정확도는 사람이 한 번 말해 봐야 한다.**
 * 여기서 잴 수 있는 것은 그 앞단이다: 인식기가 앱에 보이는가(`<queries>`), 켰을 때 «어느 엔진»이
 * 잡히는가, 껐을 때 상태가 깨끗해지는가. 이 셋이 틀리면 말해 봐야 소용이 없다.
 */
@RunWith(AndroidJUnit4::class)
class DictationTest {

    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    @Test
    fun V01_인식기가_앱에_보인다() {
        // 🚨 Android 11+ 는 `<queries>` 로 android.speech.RecognitionService 를 선언하지 않으면
        //    인식기가 깔려 있어도 여기서 false 가 나온다 — «기기에 음성 인식이 없다»로 오진하기 쉽다.
        assertTrue(
            "인식기가 안 보인다 — AndroidManifest 의 <queries> 를 확인하라",
            SpeechRecognizer.isRecognitionAvailable(ctx)
        )
    }

    @Test
    fun V02_어떤_엔진이_잡히는지_기록한다() {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        File(ctx.getExternalFilesDir(null), "dictation-engines.txt").writeText(
            "isRecognitionAvailable=" + SpeechRecognizer.isRecognitionAvailable(ctx) + "\n" +
            "isOnDeviceRecognitionAvailable=" + onDevice + "\n"
        )
        // 온디바이스가 없어도 설계상 기본 엔진으로 내려간다 — 그것만으로 실패는 아니다.
        assertTrue(SpeechRecognizer.isRecognitionAvailable(ctx) || onDevice)
    }

    @Test
    fun V03_권한이_있으면_듣기가_켜지고_엔진이_정해진다() {
        var d: Dictation? = null
        // ⚠️ SpeechRecognizer 는 메인 스레드에서만 만들고 부를 수 있다.
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
        }
        val dic = d!!
        assertTrue("권한이 있는데 듣기가 안 켜졌다: ${dic.message}", dic.listening)
        assertNotEquals("엔진이 안 정해졌다", DictationEngine.None, dic.engine)
        File(ctx.getExternalFilesDir(null), "dictation-picked.txt")
            .writeText("engine=" + dic.engine + "\n")
        inst.runOnMainSync { dic.destroy() }
    }

    @Test
    fun V05_듣는_도중_언어를_바꾸면_다시_건다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            d!!.retarget("en-US")
        }
        val dic = d!!
        assertTrue("언어를 바꿨더니 듣기가 꺼졌다: ${dic.message}", dic.listening)
        assertNotEquals(DictationEngine.None, dic.engine)
        inst.runOnMainSync { dic.destroy() }
    }

    /**
     * 🔍 온디바이스에 **어떤 언어가 실제로 깔려 있는지** 묻는다.
     * `isOnDeviceRecognitionAvailable` 은 «서비스가 있나»만 말한다 — 언어 모델이 내려와 있는지는
     * 별개다. 한국어 팩이 없으면 켜지긴 하는데 아무것도 못 알아듣는다.
     */
    @Test
    fun V06_온디바이스_언어팩을_조회한다() {
        val out = StringBuilder()
        for (loc in listOf("ko-KR", "en-US")) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var line = "$loc: (응답 없음)"
            inst.runOnMainSync {
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, loc)
                sr.checkRecognitionSupport(i, java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : android.speech.RecognitionSupportCallback {
                        override fun onSupportResult(support: android.speech.RecognitionSupport) {
                            line = "$loc: installed=" + support.installedOnDeviceLanguages +
                                " pending=" + support.pendingOnDeviceLanguages +
                                " supported=" + support.supportedOnDeviceLanguages
                            latch.countDown()
                        }
                        override fun onError(error: Int) { line = "$loc: checkError=$error"; latch.countDown() }
                    })
            }
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            out.append(line).append("\n")
        }
        File(ctx.getExternalFilesDir(null), "dictation-langs.txt").writeText(out.toString())
        assertTrue(out.isNotEmpty())
    }

    /**
     * 🚨 회귀 방지 — **켜 두면 계속 듣고 있어야 한다.**
     *
     * 예전에는 한 마디마다 인식기를 `destroy()` 하고 새로 만들었다. 무음이면 0.6초 만에
     * `NO_MATCH` 가 오고, 150ms 뒤 새 인식기를 만들면 온디바이스 엔진(SODA)의 재연결과 겹쳐
     * `ERROR_SERVER_DISCONNECTED(11)` 이 났고, 그걸 치명적 오류로 보고 꺼 버렸다.
     * → **1초도 못 버텼다.** 말할 틈조차 없었다.
     */
    @Test
    fun V07_켜두면_육초_동안_계속_듣는다() {
        var d: Dictation? = null
        inst.runOnMainSync { d = Dictation(ctx) { }; d!!.start("ko-KR") }
        val dic = d!!
        val log = StringBuilder()
        var deadAt = -1L
        repeat(12) {
            Thread.sleep(500)
            val t = (it + 1) * 500L
            log.append("t=").append(t).append("ms listening=").append(dic.listening)
                .append(" engine=").append(dic.engine)
                .append(" msg=").append(dic.message).append("\n")
            if (!dic.listening && deadAt < 0) deadAt = t
        }
        File(ctx.getExternalFilesDir(null), "dictation-6s.txt").writeText(
            log.toString() + "engine=" + dic.engine + " readyCount=" + dic.readyCount + "\n"
        )
        val ready = dic.readyCount
        val eng = dic.engine
        inst.runOnMainSync { dic.destroy() }
        assertTrue(
            "무음 " + deadAt + "ms 만에 듣기가 죽었다 (msg=" + dic.message + ") — 말할 틈이 없다",
            deadAt < 0
        )
        // 🚨 `listening` 만 보면 «켜져 있다고 믿는 상태»만 재는 것이다.
        //    마이크가 실제로 열렸는지는 onReadyForSpeech 가 왔는지로 판단한다.
        assertTrue("6초 동안 한 번도 마이크가 안 열렸다 (engine=" + eng + ")", ready > 0)
    }

    /**
     * 🚨 회귀 방지 — **BUSY 가 이어지면 스스로 꺼야 한다.**
     *
     * 실제 사고(2026-09-22): 끝난 줄 알았던 계측 테스트 프로세스가 온디바이스 인식기를 물고 있어
     * 진짜 앱이 `ERROR_RECOGNIZER_BUSY(8)` 를 0.5초마다 **20번 넘게** 받으며 헛돌았다.
     * BUSY 를 재시도 경로에만 두고 상한을 안 걸어 둔 탓이다.
     *
     * 같은 프로세스 안에서는 인식기 둘을 잡아도 충돌하지 않는다(실측) — 진짜 충돌은 프로세스 간이다.
     * 그래서 환경을 흉내 내지 않고 **핸들러를 직접 때려** 상한이 먹는지만 본다.
     * 한 번에 몰아 때려야 중간에 진짜 `ready` 가 끼어 카운터를 되돌리지 않는다.
     */
    @Test
    fun V08_BUSY_가_이어지면_상한에서_스스로_끈다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            repeat(6) { d!!.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        }
        val dic = d!!
        assertTrue("BUSY 가 6번 이어졌는데 아직 다시 걸고 있다 (msg=" + dic.message + ")", !dic.listening)
        assertTrue("왜 껐는지 안 알려 준다", dic.message != null)
        inst.runOnMainSync { dic.destroy() }
    }

    /**
     * 🚨 회귀 방지 — **껐다 바로 켜도 인식기를 새로 만들면 안 된다.**
     *
     * 사용자가 칩을 두 번 누르는 흔한 동작이다. 예전에는 `stop()` 이 인스턴스를 파괴해서,
     * 0.6초 뒤 재시작이 5~17ms 만에 `ERROR_RECOGNIZER_BUSY(8)` 를 받고 헛돌다
     * **기본 엔진(망)으로 내려갔다** — 온디바이스로 쓰겠다는 결정이 조용히 뒤집힌 것이다.
     *
     * 「온디바이스로 붙었나」로 재면 기기 상태(다른 앱·서비스 점유)에 흔들려 **깨진 채 초록**이 된다.
     * 그래서 앱이 책임지는 것만 잰다: **인스턴스를 재사용하는가.**
     */
    @Test
    fun V09_껐다_바로_켜도_인식기를_새로_만들지_않는다() {
        var d: Dictation? = null
        inst.runOnMainSync { d = Dictation(ctx) { }; d!!.start("ko-KR") }
        val dic = d!!
        Thread.sleep(800)
        val afterFirst = dic.createdCount
        inst.runOnMainSync { dic.stop() }
        Thread.sleep(300)                       // 칩을 빠르게 두 번 누르는 간격
        inst.runOnMainSync { dic.start("ko-KR") }
        Thread.sleep(200)
        val afterSecond = dic.createdCount
        inst.runOnMainSync { dic.destroy() }
        assertEquals(
            "껐다 켜면서 인식기를 새로 만들었다 — 그게 BUSY 의 원인이다",
            afterFirst, afterSecond
        )
    }

    /**
     * 🚨 **온디바이스 인식은 동시 세션이 1개다**(실측: 서비스가 «capacity is full» 이라고 찍는다).
     * 끄고 나서도 인스턴스를 계속 들고 있으면 그 한 자리를 차지해 **다른 쪽이 못 쓴다.**
     * 짧은 재토글은 재사용하되, 오래 놀면 반납해야 한다.
     */
    @Test
    fun V10_오래_쉬면_온디바이스_자리를_놓는다() {
        var d: Dictation? = null
        inst.runOnMainSync { d = Dictation(ctx) { }; d!!.start("ko-KR") }
        val dic = d!!
        Thread.sleep(800)
        val before = dic.createdCount
        inst.runOnMainSync { dic.stop() }
        Thread.sleep(5_000)                     // IDLE_RELEASE_MS(4초)보다 길게
        inst.runOnMainSync { dic.start("ko-KR") }
        Thread.sleep(300)
        val after = dic.createdCount
        inst.runOnMainSync { dic.destroy() }
        assertTrue(
            "오래 쉬었는데 인스턴스를 안 놓았다 — 온디바이스 한 자리를 계속 차지한다",
            after > before
        )
    }

    /**
     * 🚨 회귀 방지 — **끈 뒤에 오는 콜백은 «오류»가 아니다.**
     *
     * 사용자 신고(2026-09-22): 「인식은 잘 되는데 음성 인식 오류라고 뜬다」.
     * `stop()` 의 `cancel()` 에 서비스가 `ERROR_CLIENT(5)` 로 답하는데, 그걸 오류로 띄우고 있었다.
     * 받아쓰기는 멀쩡히 됐는데 화면에는 빨간 띠가 떴다 — **잘 된 일을 실패로 보고한 것이다.**
     * 뒤늦게 도착한 결과를 맥에 써 버리는 것도 같이 막는다.
     */
    @Test
    fun V11_끈_뒤에_오는_콜백은_오류로_띄우지_않는다() {
        var d: Dictation? = null
        val sent = StringBuilder()
        inst.runOnMainSync {
            d = Dictation(ctx) { sent.append(it) }
            d!!.start("ko-KR")
            d!!.stop()
            d!!.onError(SpeechRecognizer.ERROR_CLIENT)          // 취소에 대한 서비스의 답
            d!!.onResults(android.os.Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("늦게 온 결과"))
            })
        }
        val dic = d!!
        assertNull("껐는데 오류 메시지가 떴다: " + dic.message, dic.message)
        assertEquals("껐는데 뒤늦은 결과를 맥으로 보냈다", "", sent.toString())
        inst.runOnMainSync { dic.destroy() }
    }

    /**
     * 🚨 회귀 방지 — **영어로 켜도 «어떻게든» 듣는 상태가 돼야 한다.**
     *
     * 이 기기는 온디바이스에 `installed=[ko-KR]` 뿐이라 en-US 는 `ERROR_LANGUAGE_NOT_SUPPORTED(13)` 다.
     * 그건 오류가 아니라 사실이고, 앱은 기본 엔진으로 내려가 계속 들어야 한다(그리고 모델 내려받기를 건다).
     *
     * ⚠️ «온디바이스로 붙었나»로 재지 않는다 — 나중에 언어팩이 깔리면 결과가 바뀐다.
     *    기기 상태에 흔들리는 것을 게이트로 쓰면 **깨진 채 초록**이 된다(이 세션에서 이미 두 번 당했다).
     */
    @Test
    fun V12_영어로_켜도_어떻게든_듣는_상태가_된다() {
        var d: Dictation? = null
        inst.runOnMainSync { d = Dictation(ctx) { }; d!!.start("en-US") }
        val dic = d!!
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && dic.readyCount == 0) Thread.sleep(200)
        val eng = dic.engine
        val msg = dic.message
        val ready = dic.readyCount
        inst.runOnMainSync { dic.destroy() }
        File(ctx.getExternalFilesDir(null), "dictation-en.txt")
            .writeText("engine=" + eng + " readyCount=" + ready + " msg=" + msg + "\n")
        assertTrue("영어로 켰는데 8초 안에 마이크가 안 열렸다 (engine=" + eng + " msg=" + msg + ")", ready > 0)
    }

    /**
     * 🚨 회귀 방지 — **한 번 망 엔진으로 내려갔다고 영영 망에 머무르면 안 된다.**
     *
     * 사용자 신고(2026-09-22): 「잘 되긴 하는데 온디바이스가 아니라 망에서 하는 걸로 나온다」.
     * `ensure()` 가 «인스턴스가 있으면 그대로 쓴다»여서, BUSY 한 번으로 망 엔진에 내려간 뒤에는
     * 다시 켜도 그 인스턴스를 재사용했다 — 칩이 계속 `음성·망` 이었다.
     *
     * 불변식으로 잡는다: **«쓰기로 한 엔진»과 «실제 쓰는 엔진»은 항상 같아야 한다.**
     * (온디바이스가 아예 없는 기기라면 둘 다 Default 라 자연히 통과한다 — 기기에 안 흔들린다.)
     */
    @Test
    fun V13_망으로_내려가도_다시_켜면_온디바이스를_다시_시도한다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            // 인식기가 «자리가 찼다»고 답하는 상황 — 상한을 넘겨 망 엔진으로 내려간다
            repeat(3) { d!!.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        }
        val dic = d!!
        Thread.sleep(1200)
        inst.runOnMainSync { dic.stop() }
        Thread.sleep(200)
        inst.runOnMainSync {
            dic.start("ko-KR")     // start → listen(0) 이라 ensure() 가 여기서 바로 돈다
            assertEquals(
                "다시 켰는데 «쓰기로 한 엔진»과 «쓰는 엔진»이 다르다 — 옛 인스턴스를 재사용했다",
                dic.preferredEngine, dic.engine
            )
        }
        inst.runOnMainSync { dic.destroy() }
    }

    /**
     * 🚨 회귀 방지 — **«이 언어는 기기에 없다»를 영구로 박아 두면 안 된다.**
     *
     * 사용자 신고(2026-09-22): 「en-US 팩도 다운로드 받았는데 계속 망으로 잡는다」.
     * 처음 실패했을 때 `Set` 에 넣고 다시는 안 봤다 — 팩이 실제로 내려온 뒤
     * (`installed=[en-US, ko-KR]`) 에도 앱은 계속 망 엔진을 썼다.
     * 이제 시간이 지나면 다시 노려보고, 내려받기 완료 콜백이 오면 즉시 푼다.
     */
    @Test
    fun V14_기기에_없다고_영구히_막아_두지_않는다() {
        org.junit.Assume.assumeTrue(
            "온디바이스 인식기가 없는 기기라 잴 것이 없다",
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        )
        var d: Dictation? = null
        inst.runOnMainSync {
            // ⚠️ 이 테스트가 재는 것은 «블랙리스트가 만료되는가»다 — 망 우선이면 애초에
            //    온디바이스를 안 노려 단언이 무의미해진다. 기본값과 무관하게 여기서 강제한다.
            d = Dictation(ctx, onDeviceRetryMs = 300L, preferOnDeviceDefault = true) { }
            d!!.start("en-US")
            d!!.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)   // 「아직 기기에 없다」
        }
        val dic = d!!
        inst.runOnMainSync {
            assertEquals("막힌 직후에는 망 엔진이어야 한다", DictationEngine.Default, dic.preferredEngine)
        }
        Thread.sleep(700)
        inst.runOnMainSync {
            assertEquals(
                "영영 막아 뒀다 — 팩이 내려와도 온디바이스로 안 돌아온다",
                DictationEngine.OnDevice, dic.preferredEngine
            )
        }
        inst.runOnMainSync { dic.destroy() }
    }

    /**
     * 🚨 회귀 방지 — **인식기가 «ready 만 주고 안 듣는» 먹통이면 빠져나와야 한다.**
     *
     * 실측(2026-09-22): 온디바이스 서비스가 세션을 받고 `onReadyForSpeech` 까지 주면서
     * `dumpsys audio` 에 녹음 세션을 **아예 안 잡는** 상태로 빠졌다. 콜백이 하나도 안 오니
     * 앱은 영원히 기다렸고, 사용자에겐 「갑자기 한국어·영어 다 안 된다」로 보였다.
     * (서비스를 재시작하면 즉시 정상 — 앱 버그가 아니라 앱이 **못 빠져나온** 것이 문제였다.)
     *
     * 먹통 판정 시간을 아주 짧게 주면, 정상 동작 중에도 «먹통»으로 보고 **빠져나오는 경로가 도는지**
     * 확인할 수 있다. 여기서 재는 것은 판정의 정확도가 아니라 **탈출구가 있는가**다.
     */
    @Test
    fun V15_먹통이면_빠져나와_엔진을_바꾼다() {
        org.junit.Assume.assumeTrue(
            "온디바이스 인식기가 없으면 바꿔 갈 곳이 없다",
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        )
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx, onDeviceRetryMs = 60_000L, wedgeTimeoutMs = 150L,
                           preferOnDeviceDefault = true) { }
            d!!.start("ko-KR")
        }
        val dic = d!!
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && dic.engine != DictationEngine.Default) {
            Thread.sleep(200)
        }
        val eng = dic.engine
        val msg = dic.message
        inst.runOnMainSync { dic.destroy() }
        assertEquals("먹통으로 보이는데 그대로 앉아 있다 (msg=" + msg + ")", DictationEngine.Default, eng)
    }

    /**
     * 🚨 회귀 방지 — **한/영을 눌렀다고 「음성 인식 오류」가 뜨면 안 된다.**
     *
     * 사용자 신고(2026-09-22): 음성 인식을 켠 채 한/영 키를 누르면 오류 메시지가 떴다.
     * [Dictation.retarget] 이 언어를 바꾸려고 `cancel()` 을 부르는데, 그때는 **켜져 있는 상태**라
     * 「끈 뒤 콜백 무시」 가드에 안 걸렸다. 취소에 대한 `ERROR_CLIENT(5)` 가 그대로 오류로 뜬 것이다.
     */
    @Test
    fun V16_한영을_바꿔도_오류로_뜨지_않는다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            d!!.retarget("en-US")                                // 한/영 전환
            d!!.onError(SpeechRecognizer.ERROR_CLIENT)           // 취소에 대한 서비스의 답
        }
        val dic = d!!
        val msg = dic.message
        inst.runOnMainSync { dic.destroy() }
        assertNull("한/영을 바꿨을 뿐인데 오류가 떴다: " + msg, msg)
    }

    /**
     * 🚨 회귀 방지 — **망 엔진에는 분절 세션을 걸지 않는다.**
     *
     * 사용자 신고(2026-09-23): 「음성입력 아예 안 됨」. 로그를 보니 온디바이스 한국어가 먹통이 돼
     * 망 엔진으로 내려갔는데, 거기서 `onSegmentResults` 가 **빈 채로** 오고(`segment=null`)
     * 곧 `error=7`(NO_MATCH) 이 났다. `speech begin`/`speech end` 로 말은 잡히는데 글자가 안 나온다.
     * 분절 세션은 8차에 **온디바이스로만 실측**해 넣은 것인데 엔진과 무관하게 걸고 있었다.
     */
    @Test
    fun V17_망_엔진에는_분절_세션을_안_건다() {
        var d: Dictation? = null
        inst.runOnMainSync { d = Dictation(ctx) { } }
        val dic = d!!
        val onDevice = dic.intent(DictationEngine.OnDevice)
        val default = dic.intent(DictationEngine.Default)
        inst.runOnMainSync { dic.destroy() }
        assertTrue("온디바이스인데 분절 세션이 안 걸렸다",
            onDevice.hasExtra(android.speech.RecognizerIntent.EXTRA_SEGMENTED_SESSION))
        assertTrue("망 엔진에 분절 세션이 걸렸다 — 빈 분절만 오고 글자가 안 나온다",
            !default.hasExtra(android.speech.RecognizerIntent.EXTRA_SEGMENTED_SESSION))
    }

    /**
     * 🚨 **죽어 있던 로직을 배선했다.** `segmentWorks` 주석은 「한 번도 없으면 지원 안 하는 것으로
     * 본다」였는데 `segmented` 를 끄는 코드가 **어디에도 없었다**. 빈 분절이 계속 와도 그대로 돌았다.
     */
    @Test
    fun V18_빈_분절이_이어지면_분절_세션을_끈다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
        }
        val dic = d!!
        assertTrue("시작부터 분절 세션이 꺼져 있다", dic.segmentedOn)
        inst.runOnMainSync {
            repeat(Dictation.MAX_EMPTY_SEGMENTS) { dic.onSegmentResults(android.os.Bundle()) }
        }
        val on = dic.segmentedOn
        inst.runOnMainSync { dic.destroy() }
        assertTrue("빈 분절이 ${Dictation.MAX_EMPTY_SEGMENTS}회 왔는데 분절 세션을 그대로 쓴다", !on)
    }

    /**
     * 🚨 회귀 방지 — **최종 결과가 비어도 부분결과를 버리지 않는다.**
     *
     * 사용자 신고(2026-09-23): 「음성입력 아예 안 됨」. 로그를 보니 인식은 성공하고 있었다.
     *   `partial=말해 보겠습니다`  →  `final=null`   (두 번 재현)
     * 엔진이 말을 알아듣고 부분결과로 내려 준 뒤 `onResults` 를 빈 채로 줬고,
     * 우리 코드가 `if (!t.isNullOrBlank())` 에서 **통째로 버렸다.**
     * 부분결과는 `speech end` 뒤에도 한 번 더 오므로 사실상 그게 최종이다.
     */
    @Test
    fun V19_최종이_비면_부분결과를_확정으로_쓴다() {
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { got.add(it) }
            d!!.start("ko-KR")
            d!!.onPartialResults(bundleOf("말해 보겠습니다"))
            d!!.onResults(android.os.Bundle())          // 최종이 비어 온다
        }
        val dic = d!!
        inst.runOnMainSync { dic.destroy() }
        assertEquals("부분결과가 있었는데 아무것도 안 보냈다", listOf("말해 보겠습니다"), got.toList())
    }

    /** 「못 알아들었다」(NO_MATCH)로 끝나도 부분결과가 있으면 그건 알아들은 것이다. */
    @Test
    fun V20_NO_MATCH_로_끝나도_부분결과는_살린다() {
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { got.add(it) }
            d!!.start("ko-KR")
            d!!.onPartialResults(bundleOf("오겠습니다"))
            d!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        }
        val dic = d!!
        inst.runOnMainSync { dic.destroy() }
        assertEquals("NO_MATCH 라고 부분결과까지 버렸다", listOf("오겠습니다"), got.toList())
    }

    /**
     * 🚨 회귀 방지 — **먹통 판정을 기억해야 한다.**
     *
     * 전에는 그 판정이 세션 안에서만 살아 있었고 `start()` 가 매번 `preferOnDevice = true` 로
     * 되돌려서, 켤 때마다 먹통 온디바이스에 다시 걸고 **8초를 버렸다**(2026-09-23 사용자 신고:
     * 「기기에서 망으로 바뀌고부터 잘 된다」 — 즉 바뀌기 전 8초가 매번 헛돌았다).
     */
    @Test
    fun V21_먹통을_기억해_다시_켜도_바로_망으로_간다() {
        org.junit.Assume.assumeTrue(
            "온디바이스가 없으면 먹통도 안 난다",
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        )
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx, onDeviceRetryMs = 60_000L, wedgeTimeoutMs = 150L,
                           preferOnDeviceDefault = true) { }
            d!!.start("ko-KR")
        }
        val dic = d!!
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && dic.engine != DictationEngine.Default) {
            Thread.sleep(100)
        }
        assertEquals("먹통인데 안 내려갔다", DictationEngine.Default, dic.engine)
        // 껐다 다시 켠다 — 여기서 또 온디바이스로 가면 8초를 다시 버리는 것이다.
        inst.runOnMainSync { dic.stop(); dic.start("ko-KR") }
        Thread.sleep(400)
        val again = dic.engine
        inst.runOnMainSync { dic.destroy() }
        assertEquals("다시 켰더니 먹통 온디바이스로 또 갔다 — 기억을 안 한다",
            DictationEngine.Default, again)
    }

    /**
     * 2026-09-23 21차 사용자 결정 — **온디바이스 우선.** (20차에는 망 우선이었다)
     *
     * 먹통의 정체가 서비스 쪽(`com.google.android.as` 의 인식 엔진이 안 붙음)으로 갈렸고,
     * 앱은 [Dictation.NO_AUDIO_MS] 에 알아채고 빠진다(`V24`). 건강할 때 온디바이스는 분절 세션이라
     * 망의 «한 마디마다 182ms 마이크 닫힘»이 없다.
     *
     * ⚠️ «어느 쪽이 옳다»가 아니라 **결정**이다. 여기서 재는 건 «켜는 순간 무엇을 고르나»뿐이다 —
     *    그 뒤 먹통으로 내려가는지는 기기 상태에 흔들리므로 단언하지 않는다.
     */
    @Test
    fun V22_켜자마자_온디바이스로_간다() {
        org.junit.Assume.assumeTrue(
            "온디바이스 인식기가 없는 기기에선 고를 수 없다",
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        )
        var d: Dictation? = null
        var eng: DictationEngine? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            eng = d!!.engine          // start() 가 같은 틱에 인식기를 만든다
            d!!.destroy()
        }
        assertEquals("온디바이스 우선인데 켜자마자 망으로 갔다", DictationEngine.OnDevice, eng)
    }

    /**
     * 🚨 회귀 방지 — **한/영 전환 직후의 BUSY 를 «취소에 대한 답»으로 삼키면 안 된다.**
     *
     * 사용자 신고(2026-09-23): 「음성 켠 채 한/영을 바꾸면 인식이 안 되는 경우가 많다」.
     * 15차에 넣은 `ignoreNextError` 는 `cancel()` 의 답인 `ERROR_CLIENT(5)` 만 무시하려던 것인데
     * **다음 오류가 무엇이든** 삼켰다. retarget 직후엔 `ERROR_RECOGNIZER_BUSY(8)` 가 오는데
     * 그건 「새로 건 요청이 거부됐다」는 진짜 실패다 — 삼키면 BUSY 처리가 한 번도 안 돈다.
     * 실측: `retarget → ko-KR` 152ms 뒤 `error=8` 이 삼켜지고 1.7초 공백.
     */
    @Test
    fun V23_한영_전환_직후의_BUSY_는_삼키지_않는다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            d!!.retarget("en-US")                                      // 한/영 전환
            d!!.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)        // 새 요청이 거부됐다
        }
        val dic = d!!
        val busy = dic.busyCount
        inst.runOnMainSync { dic.destroy() }
        assertEquals("BUSY 를 «취소에 대한 답»으로 오인해 삼켰다 — 처리가 안 돈다", 1, busy)
    }

    /**
     * 🚨 회귀 방지 — **온디바이스가 `ready` 만 주고 소리를 안 보내면 2초 안에 망으로 간다.**
     *
     * 2026-09-23 실측: 먹통일 때 `com.google.android.as` 는 마이크를 열고 소리를 파이프에 붓지만
     * 그걸 읽을 인식 엔진(SODA)이 안 붙는다. 우리에겐 `ready` 만 오고 RMS 는 0 이다.
     * 건강하면 첫 RMS 가 0.8초쯤 온다. 전에는 «콜백이 8초 동안 하나도 없으면»으로 갈라서,
     * `ready` 가 감시 타이머를 되돌려 놓은 채 켤 때마다 8초를 버렸다.
     *
     * 옛 경로(8초 먹통 판정)는 60초로 밀어 막는다 — 새 판정만으로 내려가는지 본다.
     * 판정 시간(150ms)이 건강한 첫 RMS(~800ms)보다 짧아서, 기기 상태와 무관하게 이 경로가 돈다.
     */
    @Test
    fun V24_ready_뒤_소리가_안_오면_빨리_망으로_간다() {
        org.junit.Assume.assumeTrue(
            "온디바이스가 없으면 먹통도 안 난다",
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        )
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx, onDeviceRetryMs = 60_000L, wedgeTimeoutMs = 60_000L,
                           noAudioMs = 150L, preferOnDeviceDefault = true) { }
            d!!.start("ko-KR")
            // 🚨 `ready` 는 **직접** 준다. 진짜 `ready` 를 기다리면 앞 테스트의 온디바이스 세션이
            //    아직 자리를 쥐고 있을 때 3초 안에 안 와서, 스위트에서만 빨갛게 됐다(21차 실측 —
            //    단독으로는 초록). 여기서 재는 건 «ready 뒤 소리가 없으면 빠지는가»뿐이다.
            d!!.onReadyForSpeech(null)
        }
        val dic = d!!
        // 🚨 스위트에서는 앞 테스트가 자리를 쥐고 있어 진짜 인식기가 BUSY 를 주고, 그 처리의 `listen()` 이
        //    대기 중인 판정까지 지운다(21차 실측 — 단독 초록, 스위트 빨강 두 번). 제품에선 BUSY 경로가
        //    따로 내려가니 문제가 아니다. 여기서는 판정이 한 번 돌 기회를 보장하려고 ready 를 다시 준다.
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline && dic.engine != DictationEngine.Default) {
            Thread.sleep(500)
            if (dic.engine == DictationEngine.OnDevice) inst.runOnMainSync { dic.onReadyForSpeech(null) }
        }
        val eng = dic.engine
        inst.runOnMainSync { dic.destroy() }
        assertEquals("ready 뒤 소리가 없는데 온디바이스에 앉아 있다", DictationEngine.Default, eng)
    }

    private fun bundleOf(vararg text: String) = android.os.Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(text.toList()))
    }

    @Test
    fun V04_정지하면_상태가_깨끗해진다() {
        var d: Dictation? = null
        inst.runOnMainSync {
            d = Dictation(ctx) { }
            d!!.start("ko-KR")
            d!!.stop()
        }
        val dic = d!!
        assertTrue("정지했는데 듣고 있다", !dic.listening)
        assertTrue("정지했는데 부분 결과가 남았다", dic.partial.isEmpty())
        // 엔진(인스턴스)은 일부러 살려 둔다 — 곧 다시 켤 때 BUSY 를 피하려고.
        inst.runOnMainSync { dic.destroy() }
    }
}

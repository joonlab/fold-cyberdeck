#!/bin/bash
# foldlab 개발 헬퍼 — 빌드 · 무선 페어링 · 설치 · 계측 테스트를 한 곳에서
set -e
# JDK 21 · Android SDK 위치는 환경변수로 덮어쓸 수 있다(기본값은 Homebrew openjdk@21 · Android Studio 기본 SDK).
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
P="$(cd "$(dirname "$0")" && pwd)"
APK="$P/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$P/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PKG=kr.joonlab.foldlab

# ⚠️ 같은 폴드8 이 두 포트(:5555 와 무선디버깅 임의포트)로 동시에 잡힌다.
#    `-s` 없이 adb 를 부르면 "more than one device" 로 죽으므로 하나를 골라 고정한다.
#    FOLD_SERIAL 로 덮어쓸 수 있다.
pick_device() {
  if [ -n "$FOLD_SERIAL" ]; then echo "$FOLD_SERIAL"; return; fi
  # :5555 를 우선, 없으면 첫 번째 device
  # 🚨 `… | grep | head -1 && return` 으로 쓰면 안 된다 — 파이프라인의 종료코드는 head 의 것(0)이라
  #    :5555 가 없어도 빈 줄로 return 해서 «무선디버깅 포트만 있는» 경우가 늘 「기기 없음」이 됐다.
  local all; all=$(adb devices | awk '$2=="device"{print $1}')
  echo "$all" | grep ':5555$' | head -1 | grep . || echo "$all" | head -1
}
S=""
need_device() {
  S="$(pick_device)"
  [ -n "$S" ] || { echo "❌ 연결된 기기가 없다 — ./dev.sh connect <IP:포트>"; exit 1; }
  export ANDROID_SERIAL="$S"
  echo "📱 기기: $S"
}

case "${1:-run}" in
  pair)   # ./dev.sh pair <IP:페어링포트> <6자리코드>
    adb pair "$2" "$3" ;;
  connect) # ./dev.sh connect <IP:접속포트>
    adb connect "$2" ;;
  build)
    "$P/gradlew" -p "$P" assembleDebug ;;
  install)
    need_device; adb -s "$S" install -r "$APK" ;;
  run)    # 빌드 → 설치 → 실행 (가장 자주 쓰는 것)
    need_device
    "$P/gradlew" -p "$P" assembleDebug
    adb -s "$S" install -r "$APK"
    adb -s "$S" shell am start -n $PKG/.MainActivity
    echo "✅ 폰에서 FoldLab 이 떴습니다" ;;

  test)   # 멀티터치 등 제스처 계측 테스트. ./dev.sh test [클래스#메서드]
    # 🚨 `./gradlew connectedDebugAndroidTest` 를 쓰지 않는다.
    #    AGP 9.4.1 + UTP 조합에서 **자명하게 통과하는 ASCII 테스트 하나만 돌려도**
    #    exit code 1 을 써서 BUILD FAILED 가 된다(XML·HTML 리포트는 "0 failures / 100% successful").
    #    초록을 빨강으로 보고하는 러너는 쓸 수 없으므로 instrumentation 을 직접 부른다.
    need_device
    "$P/gradlew" -p "$P" assembleDebug assembleDebugAndroidTest
    adb -s "$S" install -r "$APK" >/dev/null
    adb -s "$S" install -r "$TEST_APK" >/dev/null
    FILTER=""
    [ -n "$2" ] && FILTER="-e class $2"
    # shellcheck disable=SC2086
    adb -s "$S" shell am instrument -w $FILTER \
      $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/foldlab-test.txt
    # 🚨 계측 프로세스를 반드시 죽인다.
    #    안 죽이면 **마이크·음성 인식기 같은 «기기에 하나뿐인» 자원을 쥔 채 남는다.**
    #    실제 사고(2026-09-22): 남은 test 프로세스가 온디바이스 인식기를 물고 있어
    #    진짜 앱이 계속 ERROR_RECOGNIZER_BUSY(8) 를 받았고, 앱 버그로 오진할 뻔했다.
    adb -s "$S" shell am force-stop $PKG.test >/dev/null 2>&1
    # 🚨 온디바이스 인식 서비스도 재시작한다.
    #    2026-09-23 실측: 스위트를 돌리고 나면 `com.google.android.as` 의 인식 엔진(SODA)이 안 붙는
    #    먹통이 된다 — `ready` 는 오는데 RMS 가 0 (`OnDeviceProbeTest`). 단일 테스트로는 재현이 안 되고
    #    여러 개를 이어 돌려야 난다(누적). 서비스 재시작이면 즉시 낫는다(`doctor fix` 와 같은 동작,
    #    서비스는 필요할 때 자동으로 다시 뜬다). 20차의 「doctor fix 로도 안 나았다」는
    #    고친 뒤 테스트를 돌려 다시 빠진 것이었다.
    adb -s "$S" shell am force-stop com.google.android.as >/dev/null 2>&1
    grep -q '^OK (' /tmp/foldlab-test.txt || { echo "❌ 테스트 실패"; exit 1; }
    ;;

  doctor) # 받아쓰기·마이크 진단.  ./dev.sh doctor [fix]
    need_device
    echo "── 녹음 세션 (active? true 면 누가 마이크를 쥐고 있다)"
    adb -s "$S" shell dumpsys audio 2>/dev/null | grep -A3 "## RecordActivityMonitor" || true
    echo
    echo "── foldlab 프로세스 (.test 가 남아 있으면 그게 범인이다)"
    adb -s "$S" shell ps -A 2>/dev/null | grep -i foldlab || echo "  (없음)"
    echo
    echo "── 온디바이스 인식 서비스가 자리를 내주나 (동시 세션 1개)"
    AS=$(adb -s "$S" shell pidof com.google.android.as 2>/dev/null | tr -d "\r")
    if [ -n "$AS" ]; then
      FULL=$(adb -s "$S" shell logcat -d --pid="$AS" 2>/dev/null | grep -c "capacity is full" || true)
      echo "  capacity is full 로그: ${FULL:-0} 건"
    else
      echo "  (서비스가 안 떠 있다)"
    fi
    echo
    echo "── 최근 Dictation 로그"
    adb -s "$S" shell logcat -d -s Dictation:* 2>/dev/null | tail -15 || true
    if [ "${2:-}" = "fix" ]; then
      echo
      echo "🔧 남은 계측 프로세스와 온디바이스 인식 서비스를 정리한다(서비스는 필요할 때 자동으로 다시 뜬다)"
      adb -s "$S" shell am force-stop $PKG.test >/dev/null 2>&1 || true
      adb -s "$S" shell am force-stop com.google.android.as >/dev/null 2>&1 || true
      echo "✅ 완료 — 앱에서 «음성» 칩을 다시 켜 보라"
    fi
    ;;

  log)
    need_device; adb -s "$S" logcat --pid="$(adb -s "$S" shell pidof $PKG)" ;;
  devices)
    adb devices -l ;;
  *) echo "사용: ./dev.sh [pair|connect|build|install|run|test|doctor|log|devices]" ;;
esac

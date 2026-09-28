#!/bin/bash
# deckd 실행기 — Tailscale 인터페이스에만 바인딩한다(전 인터페이스 노출 금지).
set -uo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
D="$(cd "$(dirname "$0")" && pwd)"
LABEL=com.joonlab.deckd
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/deckd.log"
# 상주용으로 «복사해 둔» 바이너리를 먼저 쓴다.
# 🚨 TCC(화면 기록·손쉬운 사용) 허용은 **바이너리 해시에 묶인다** — 개발 중 `swift build` 를 할 때마다
#    돌고 있는 서비스의 권한이 날아가면 못 쓴다. 그래서 상주본은 따로 떼어 둔다.
SERVED="$HOME/.local/bin/deckd"
ARGS_FILE="$HOME/.config/deckd/args"   # 머신별 추가 인자(있으면 serve 가 읽는다)
BIN="$D/.build/release/deckd"
[ -x "$BIN" ] || BIN="$D/.build/debug/deckd"

# ── 자체 서명 (2026-09-24)
# ad-hoc 서명은 TCC 승인이 cdhash 에 묶여 **빌드마다** ⊖⊕ 가 필요했다.
# 자체 서명하면 TCC 가 기록하는 요구조건이 `identifier "deckd" and certificate root = H"…"` 가 되어
# **내용이 바뀌어도 같은 인증서로 서명한 한 유지된다.** 두 맥이 같은 인증서를 쓴다(sign-setup 이 옮겨 심는다).
# ⚠️ 이 키로 서명한 것은 무엇이든 deckd 의 두 권한을 물려받는다 — 키는 이 맥의 전용 키체인에만 둔다.
SIGN_DIR="$HOME/.config/deckd/signing"
SIGN_KC="$HOME/Library/Keychains/deckd-signing.keychain-db"

sign_hash() { /usr/bin/openssl x509 -in "$SIGN_DIR/cert.pem" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g'; }

sign_bin() {
  local b="$1"
  if [ -f "$SIGN_KC" ] && [ -f "$SIGN_DIR/keychain.pw" ] && [ -f "$SIGN_DIR/cert.pem" ]; then
    security unlock-keychain -p "$(cat "$SIGN_DIR/keychain.pw")" "$SIGN_KC" || { echo "❌ 서명 키체인 잠금 해제 실패"; return 1; }
    # 🚨 `codesign --keychain` 만으로는 신원을 못 찾는다(«no identity found», 2026-09-24 실측) —
    #    키체인이 **검색 목록**에 있어야 한다. 서명하는 동안만 넣고 원래 목록으로 되돌린다.
    #    (신뢰 설정은 필요 없다 — CSSMERR_TP_NOT_TRUSTED 인 채로 서명된다)
    local saved=() line rc=0
    while IFS= read -r line; do
      line="${line#"${line%%[![:space:]]*}"}"; line="${line#\"}"; line="${line%\"}"
      [ -n "$line" ] && saved+=("$line")
    done < <(security list-keychains -d user)
    # 원래 목록을 못 읽었으면 건드리지 않는다 — 빈 목록으로 -s 하면 login 키체인까지 빠진다
    [ ${#saved[@]} -gt 0 ] || { echo "❌ 키체인 검색 목록을 못 읽었다 — 서명 중단"; return 1; }
    security list-keychains -d user -s "${saved[@]}" "$SIGN_KC"
    codesign --force --sign "$(sign_hash)" --identifier deckd "$b" 2>&1 || rc=1
    security list-keychains -d user -s "${saved[@]}"
    [ $rc -eq 0 ] || { echo "❌ 서명 실패"; return 1; }
    echo "🔏 자체 서명 — $(codesign -d -r- "$b" 2>&1 | sed -n 's/^designated => //p')"
  else
    echo "⚠️  자체 서명 신원이 없다 — ad-hoc 이라 install 마다 TCC ⊖⊕ 가 필요하다. 심으려면:  $0 sign-setup"
  fi
}

ts_addr() {
  ifconfig 2>/dev/null \
    | grep -oE 'inet 100\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\.[0-9.]+' \
    | awk '{print $2}' | head -1
}

case "${1:-run}" in
  build)
    swift build -c release --package-path "$D" || exit 1
    sign_bin "$D/.build/release/deckd" || exit 1
    echo "✅ $D/.build/release/deckd"; exit 0 ;;

  sign-setup) # 자체 서명 신원을 이 맥에 심는다(한 번). 인증서가 없으면 만든다.
    # 두 번째 맥에는 첫 맥의 $SIGN_DIR 를 그대로 복사해 온 뒤 이걸 돌린다 — 인증서가 같아야 요구조건이 같다.
    mkdir -p "$SIGN_DIR"; chmod 700 "$SIGN_DIR"
    if [ ! -f "$SIGN_DIR/cert.pem" ]; then
      /usr/bin/openssl rand -hex 24 > "$SIGN_DIR/p12.pw"
      /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
        -keyout "$SIGN_DIR/key.pem" -out "$SIGN_DIR/cert.pem" -subj "/CN=deckd self-signed" \
        -addext "keyUsage=critical,digitalSignature" -addext "extendedKeyUsage=critical,codeSigning" \
        -addext "basicConstraints=critical,CA:false" 2>/dev/null || { echo "❌ 인증서 생성 실패"; exit 1; }
      # LibreSSL 의 기본 p12 알고리즘은 security import 가 읽는다(OpenSSL 3 기본값은 못 읽는다)
      /usr/bin/openssl pkcs12 -export -inkey "$SIGN_DIR/key.pem" -in "$SIGN_DIR/cert.pem" \
        -out "$SIGN_DIR/deckd-sign.p12" -passout "pass:$(cat "$SIGN_DIR/p12.pw")" || { echo "❌ p12 실패"; exit 1; }
      echo "🆕 인증서를 만들었다 — SHA-1 $(sign_hash)"
    fi
    chmod 600 "$SIGN_DIR"/*
    if [ ! -f "$SIGN_KC" ]; then
      [ -f "$SIGN_DIR/keychain.pw" ] || /usr/bin/openssl rand -hex 24 > "$SIGN_DIR/keychain.pw"
      chmod 600 "$SIGN_DIR/keychain.pw"
      KPW="$(cat "$SIGN_DIR/keychain.pw")"
      security create-keychain -p "$KPW" "$SIGN_KC" || { echo "❌ 키체인 생성 실패"; exit 1; }
      security set-keychain-settings "$SIGN_KC"          # 자동 잠금 없음
      security unlock-keychain -p "$KPW" "$SIGN_KC"
      security import "$SIGN_DIR/deckd-sign.p12" -k "$SIGN_KC" -P "$(cat "$SIGN_DIR/p12.pw")" -T /usr/bin/codesign \
        || { echo "❌ p12 가져오기 실패"; exit 1; }
      # 이게 없으면 codesign 이 키를 쓸 때 GUI 허용 창을 띄운다 — ssh 에선 거기서 멈춘다
      security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KPW" "$SIGN_KC" >/dev/null \
        || { echo "❌ partition-list 실패"; exit 1; }
    fi
    echo "✅ 서명 신원 준비 — SHA-1 $(sign_hash) · 키체인 $SIGN_KC"
    exit 0 ;;
  stop)
    # launchd 에 등록돼 있으면 죽여도 바로 되살아난다 — 먼저 내려 준다.
    launchctl bootout "gui/$UID/$LABEL" 2>/dev/null && echo "(launchd 에서 내림 — 다시 살리려면 $0 install)"
    lsof -ti:8790 2>/dev/null | xargs -r kill && echo "✅ 정지" || echo "(돌고 있지 않음)"; exit 0 ;;
  status)
    if launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; then
      echo "launchd: 등록됨 ($LABEL)"
      launchctl print "gui/$UID/$LABEL" 2>/dev/null | grep -E '^\s*(state|pid|last exit) ' | sed 's/^/  /'
    else
      echo "launchd: 등록 안 됨  →  $0 install 로 재부팅에도 살려 둘 수 있다"
    fi
    lsof -nP -iUDP:8790 2>/dev/null | tail -n +2 || echo "(포트 8790 비어 있음)"
    exit 0 ;;
  token)
    cat ~/.config/deckd/token; exit 0 ;;

  serve)   # launchd 전용. 부팅 직후엔 Tailscale 이 아직 안 올라와 있으니 기다렸다 띄운다.
    [ -x "$SERVED" ] && BIN="$SERVED"
    [ -x "$BIN" ] || { echo "❌ 빌드가 없다: $BIN"; exit 1; }
    # ⚠️ 무한 대기는 금지 — 못 올라오면 실패로 끝내고 launchd 가 다시 부르게 둔다.
    TS=""
    for _ in $(seq 1 60); do
      TS="$(ts_addr)"; [ -n "$TS" ] && break
      sleep 2
    done
    [ -n "$TS" ] || { echo "❌ Tailscale 주소를 120초 기다렸는데 안 올라왔다"; exit 1; }
    # 머신마다 다른 인자는 이 파일에 둔다. plist 에 적으면 `install` 이 다시 쓰면서 날아간다.
    # 예) 인터넷 너머로 보내는 맥:  echo "--burst-mbps 12" > ~/.config/deckd/args
    EXTRA=()
    if [ -f "$ARGS_FILE" ]; then
      while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in ''|'#'*) continue ;; esac
        # shellcheck disable=SC2206
        EXTRA+=($line)
      done < "$ARGS_FILE"
      [ ${#EXTRA[@]} -gt 0 ] && echo "⚙️  추가 인자($ARGS_FILE): ${EXTRA[*]}"
    fi
    echo "🎛  $(date '+%F %T')  deckd → $TS:8790 (UDP, Tailscale 전용)  bin=$BIN"
    # bash 3.2 에서 빈 배열은 set -u 에 걸린다 — 이 관용구로 편다.
    exec "$BIN" --bind "$TS" --port 8790 ${EXTRA[@]+"${EXTRA[@]}"} "${@:2}"
    ;;

  install) # 재부팅·로그아웃에도 살아 있게 launchd 에 등록한다
    [ -x "$D/.build/release/deckd" ] || { echo "먼저 릴리스 빌드:  $0 build"; exit 1; }
    mkdir -p "$HOME/.local/bin" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
    cp -f "$D/.build/release/deckd" "$SERVED"
    cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$D/run.sh</string>
    <string>serve</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
PL
    launchctl bootout "gui/$UID/$LABEL" 2>/dev/null
    # ⚠️ bootout 직후 바로 bootstrap 하면 «Input/output error(5)» 로 떨어진다 —
    #    launchd 가 옛 잡을 내리는 데 시간이 걸린다(2026-09-23 실측). 잠깐 두고 한 번 더 시도한다.
    sleep 1
    launchctl bootstrap "gui/$UID" "$PLIST" 2>/dev/null || {
      sleep 2
      launchctl bootstrap "gui/$UID" "$PLIST" || { echo "❌ bootstrap 실패"; exit 1; }
    }
    launchctl kickstart -k "gui/$UID/$LABEL" 2>/dev/null
    echo "✅ 등록: $PLIST"
    echo "   상주본: $SERVED   로그: $LOG"
    echo
    echo "🚨 권한 주체가 «터미널»에서 «deckd 바이너리»로 바뀐다 — 두 개를 다시 허용해야 한다:"
    echo "   시스템 설정 → 개인정보 보호 및 보안 → 화면 기록 및 시스템 오디오 녹음 → + → $SERVED"
    echo "   같은 화면 → 손쉬운 사용 → + → $SERVED"
    echo "   (파일 선택창에서 ⌘⇧G 로 경로를 그대로 붙여넣으면 된다)"
    echo "   허용 뒤:  $0 restart"
    exit 0 ;;

  uninstall)
    launchctl bootout "gui/$UID/$LABEL" 2>/dev/null && echo "✅ 내림" || echo "(등록돼 있지 않음)"
    rm -f "$PLIST"; exit 0 ;;

  restart)
    launchctl kickstart -k "gui/$UID/$LABEL" 2>/dev/null && echo "✅ 재시작" \
      || echo "(등록돼 있지 않다 — $0 install)"
    exit 0 ;;

  golden) # Protocol.swift ↔ 폰 Protocol.kt 가 바이트 단위로 같은지 본다(DISPLAYS 골든).
    # 🚨 한쪽만 고치면 «조용히» 어긋난다 — 폰은 잘린 패킷으로 보고 목록을 버리고, 칩이 안 뜬다.
    # 모노레포 기준: mac/ 옆의 android/. 다른 위치면 FOLDLAB_DIR 로 덮어쓴다.
    KT="${FOLDLAB_DIR:-$D/../android}/app/src/main/java/kr/joonlab/foldlab/net/Protocol.kt"
    SW=$("$D/.build/release/deckd" --print-protocol-golden 2>/dev/null || "$D/.build/debug/deckd" --print-protocol-golden)
    KV=$(grep -oE 'GOLDEN_HEX = "[0-9a-f]+"' "$KT" | sed -E 's/.*"([0-9a-f]+)"/\1/')
    BAD=0
    if [ -n "$SW" ] && [ "$SW" = "$KV" ]; then echo "✅ DISPLAYS 골든 일치 (${#SW}자)"
    else echo "❌ DISPLAYS 골든 불일치"; echo "  swift : $SW"; echo "  kotlin: $KV"; BAD=1; fi
    # INPUT 은 방향이 반대다 — 폰이 인코딩(HEX), deckd 가 푼다. 푼 결과가 폰이 기대한 뜻(DECODED)과 같아야 한다.
    IH=$(grep -oE 'const val HEX = .*' "$KT" | grep -oE '"[0-9a-f]+"' | tr -d '"\n')
    ID=$(grep -oE 'const val DECODED = "[^"]+"' "$KT" | sed -E 's/.*"([^"]+)"/\1/')
    IS=$("$D/.build/release/deckd" --decode-input "$IH" 2>/dev/null || "$D/.build/debug/deckd" --decode-input "$IH")
    if [ -n "$IH" ] && [ "$IS" = "$ID" ]; then echo "✅ INPUT 골든 일치 — $IS"
    else echo "❌ INPUT 골든 불일치"; echo "  hex   : $IH"; echo "  swift : $IS"; echo "  kotlin: $ID"; BAD=1; fi
    exit $BAD ;;

  tcc)  # 권한이 «지금 이 바이너리»에 실제로 붙어 있는지 본다.
    # 🚨 auth_value=2 만 보면 틀린다. 승인은 바이너리의 **cdhash 에 묶여** 있어서,
    #    다시 빌드해 install 하면 행은 auth=2 로 남은 채 효력만 사라진다.
    #    그때 시스템 설정에는 **스위치가 켜진 것으로 보인다** — 「이미 켜져 있는데?」가 된다.
    #    (2026-09-23 홈맥에서 실제로 당함)
    DB="/Library/Application Support/com.apple.TCC/TCC.db"
    BINP="$SERVED"; [ -x "$BINP" ] || BINP="$BIN"
    CUR="$(codesign -dvvv "$BINP" 2>&1 | awk -F= '/^CDHash=/{print tolower($2)}')"
    echo "바이너리: $BINP"
    echo "  지금 CDHash: ${CUR:-(못 읽음)}"
    ROWS="$(sqlite3 "$DB" "select service||'|'||auth_value||'|'||hex(csreq) from access where client='$BINP';" 2>/dev/null)"
    if [ -z "$ROWS" ]; then
      echo "  (TCC 에 행이 없다 — 아직 «안 물어봄». 시스템 설정에서 ＋ 로 추가한다)"
      exit 0
    fi
    echo "$ROWS" | while IFS='|' read -r svc auth req; do
      # 🚨 csreq 는 승인 당시 «누구를 믿었나»(요구조건)다. ad-hoc 이면 `cdhash H"…"`, 자체 서명이면
      #    `identifier "deckd" and certificate root = H"…"`. 꼬리 해시를 잘라 비교하던 방식(길이 접두 바이트에
      #    한 번 속았다)을 버리고 **지금 바이너리가 그 요구조건을 만족하는지 codesign 에 직접 묻는다.**
      rec="$(printf '%s' "$req" | xxd -r -p | csreq -r /dev/stdin -t 2>/dev/null)"
      if [ "$auth" != "2" ]; then
        echo "  ❌ $svc  auth=$auth (거부) → 목록의 **토글을 켠다**(＋ 아님)"
      elif [ -n "$rec" ] && codesign -v -R="$rec" "$BINP" 2>/dev/null; then
        echo "  ✅ $svc  auth=2 · 요구조건 충족 — $rec"
      else
        echo "  ⚠️  $svc  auth=2 인데 **지금 바이너리가 승인된 신원이 아니다** — 옛 빌드·옛 서명의 승인이다"
        echo "       승인된 요구조건: ${rec:-(못 읽음)}"
        echo "       → 목록에서 ⊖ 로 **지우고** ⊕ 로 다시 추가한다(⌘⇧G 로 $BINP)"
        echo "       ⚠️ 껐다 켜기로는 안 낫는다 — 토글은 auth_value·last_modified 만 갱신하고"
        echo "          csreq(승인된 코드 신원)는 보존한다. 스위치는 켜진 것처럼 보인다."
      fi
    done
    exit 0 ;;

  logs)
    tail -n "${2:-40}" "$LOG" 2>/dev/null || echo "(로그 없음: $LOG)"; exit 0 ;;
esac

[ -x "$BIN" ] || { echo "❌ 먼저 빌드하라:  $0 build"; exit 1; }

TS="$(ts_addr)"
if [ -z "$TS" ]; then
  echo "⚠️  Tailscale 주소를 못 찾았다. 0.0.0.0 으로 열면 같은 망의 누구나 화면을 본다."
  echo "    그래도 열려면:  $BIN --bind 0.0.0.0 ..."
  exit 1
fi

# 이미 떠 있으면 새로 띄우지 않는다 — SO_REUSEADDR 라 둘 다 붙어 조용히 엉킨다
if lsof -ti:8790 >/dev/null 2>&1; then
  echo "이미 실행 중:"; lsof -nP -iUDP:8790 | tail -n +2; exit 0
fi

echo "🎛  deckd → $TS:8790 (UDP, Tailscale 전용)"
echo "🔑 토큰: $(cat ~/.config/deckd/token 2>/dev/null || echo '(첫 실행 시 생성됨)')"
exec "$BIN" --bind "$TS" --port 8790 "${@:2}"

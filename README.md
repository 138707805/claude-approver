# Claude Approver

Claude Code가 터미널에서 "이 명령을 허용할까요?" 같은 권한 승인 질문을 띄울 때,
PC 앞에 없어도 휴대폰 알림으로 받아서 그 자리에서 허용/거부할 수 있게 해주는 도구입니다.

## 어떻게 동작하나요

1. Claude Code가 Bash/Edit/Write 같은 도구를 쓰기 직전에, PC에 설치된 훅 스크립트가 가로챕니다.
2. 훅 스크립트가 질문 내용을 `ntfy.sh`(무료 오픈소스 알림 중계 서비스)로 보냅니다.
3. 휴대폰의 Claude Approver 앱이 알림을 받아서 "허용 / 거부" 버튼을 보여줍니다.
4. 버튼을 누르면 그 결과가 다시 `ntfy.sh`를 거쳐 PC의 훅 스크립트로 전달되고,
   Claude Code는 그 결정대로 진행합니다.
5. 만약 정해진 시간(기본 170초) 안에 휴대폰에서 응답이 없으면, 평소처럼 터미널에
   승인 질문이 그대로 뜹니다. 즉 폰을 못 봐도 아무것도 막히지 않습니다.
6. 한 번 "허용"한 요청은 PC에 기억됩니다 — 다음에 **완전히 똑같은** 요청이 오면
   (Bash는 명령어 전체가 같을 때, Write/Edit는 같은 파일일 때) 휴대폰에 묻지 않고
   바로 자동 승인됩니다. "거부"는 기억하지 않습니다(다음에 또 물어봅니다).
7. 실수로 알림을 밀어서 지워버렸어도 괜찮습니다 — 앱을 열어 "최근 요청" 목록에서
   아직 대기 중인 항목을 찾아 그 자리에서 허용/거부할 수 있습니다.

**보안 관련 참고**: `ntfy.sh`는 일반 인터넷(HTTPS)로 통신하는 공개 서비스입니다.
연결에 쓰이는 "페어링 코드"는 추측하기 어려운 무작위 문자열이라 사실상 나만 아는
비공개 채널처럼 쓰이지만, 절대 뚫리지 않는 암호화 채널은 아닙니다. 코드를 다른
사람과 공유하지 마세요.

## PC 쪽 설정

```bash
node ~/claude-approver/hook/setup.js
```

실행하면 무작위 페어링 코드가 만들어지고, `~/.claude/settings.json`에 훅이
자동으로 등록됩니다. 기존 설정 파일은 `settings.json.claude-approver-backup`으로
백업됩니다. 화면에 뜨는 코드(`ca-xxxxxxxxxxxx` 형태)를 복사해두세요.

## 휴대폰 쪽 설정

1. Claude Approver APK를 설치합니다.
2. 앱을 열고 위에서 복사한 페어링 코드를 붙여넣습니다.
3. "연결하고 감시 시작" 버튼을 누릅니다.
4. 알림 권한을 허용하고, 배터리 최적화 제외 화면이 뜨면 허용해주세요
   (허용하지 않으면 화면이 꺼졌을 때 안드로이드가 앱을 죽여서 알림이 안 올 수 있습니다).

이제 PC에서 Claude Code가 Bash/Edit/Write 권한을 물어볼 때마다 휴대폰으로 알림이 옵니다.

## 감시 대상 도구 바꾸기

기본값은 `Bash|Edit|Write`입니다. 다른 도구도 포함하거나 줄이고 싶으면
`~/.claude/settings.json`의 `hooks.PreToolUse[].matcher` 값을 수정하세요.

## 되돌리기 / 끄기

- 훅만 끄려면: `~/.claude/settings.json`에서 Claude Approver 관련 항목을 지우거나,
  백업 파일(`settings.json.claude-approver-backup`)로 원상복구하면 됩니다.
- 앱만 끄려면: 앱에서 "감시 중지"를 누르면 됩니다.
- "항상 허용" 기억을 초기화하려면: PC에서 `~/.claude/claude-approver-allowlist.json`
  파일을 지우면 됩니다. 다음부터 모든 요청을 다시 물어봅니다.

## 앱 업데이트하기

앱 서명을 `android/debug.keystore`로 고정해뒀기 때문에, v1.1부터는 새 APK를
그냥 기존 앱 위에 덮어 설치하면 됩니다(삭제 후 재설치할 필요 없음, 페어링
코드도 유지됩니다). **단, v1.0에서 v1.1로 처음 업데이트할 때는 서명이 달라서
한 번은 기존 앱을 삭제하고 새로 설치해야 합니다.**

## 폴더 구조

```
claude-approver/
  hook/                    PC(Claude Code)에서 실행되는 Node.js 훅 스크립트
    permission-relay.js    실제 승인 중계 로직 (PreToolUse 훅)
    setup.js               최초 1회 설정 스크립트
  android/                 안드로이드 앱 (Kotlin, Gradle)
  .github/workflows/       GitHub Actions로 APK 자동 빌드
```

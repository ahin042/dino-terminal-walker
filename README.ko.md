# Dino Walker

[English](README.md) | **한국어**

IDE 터미널 상단 가장자리를 따라 작은 픽셀 공룡이 좌우로 걸어 다니게 해 주는 JetBrains IDE 플러그인입니다
(IntelliJ IDEA, WebStorm 및 2024.2 이상의 모든 IntelliJ 기반 IDE).

IDE 터미널에 `start dino`를 입력하면 공룡이 나타납니다. `exit dino`를 입력하면 운석이 알아서 처리해 줍니다.

## 설치

### 빌드 결과물로 설치

```bash
./gradlew buildPlugin
```

그런 다음 IDE에서 **Settings → Plugins → ⚙ → Install Plugin from Disk…** 를 열고
`build/distributions/dino-walker-<version>.zip`을 선택하세요. 이 플러그인은 동적 플러그인이라 보통 재시작이 필요 없습니다.

### 샌드박스 IDE에서 체험하기

```bash
./gradlew runIde
```

요구 사항: JDK 21. 나머지는 Gradle wrapper가 알아서 내려받습니다.

## 사용법

| IDE 터미널에서   | 동작 |
|-------------|---|
| `start dino` | 터미널 도구 창 위에 공룡이 나타나 걷기 시작합니다. |
| `exit dino` | 공룡이 멈추고, 운석이 떨어지고, 세 번 깜빡인 뒤 사라집니다. |
| 디노 클릭| 원작 게임의 점프 물리로 점프합니다. |


## 셸 설정 대체 방법

`start dino` / `exit dino`가 인식되지 않는 경우(예: **Settings → Tools → Terminal**에서 IDE의 셸 통합을 꺼 둔 경우,
또는 셸이 특이한 방식으로 시작되는 경우) `~/.bashrc` 또는 `~/.zshrc`에 아래 줄을 추가하세요.

```sh
[ -n "$DINO_WALKER_INIT" ] && . "$DINO_WALKER_INIT"
```

IDE 터미널 밖에서는 아무 일도 하지 않습니다. `dino start` / `dino exit`와 Find Action 항목은 이 설정 없이도
동작합니다.

## 빌드

```bash
./gradlew buildPlugin    # build/distributions/*.zip
./gradlew verifyPlugin   # IDEA 2024.2.6, IDEA 2026.3, WebStorm 2026.3 대상 IntelliJ Plugin Verifier
```
## 크레딧 / 라이선스

- **플러그인 코드**: MIT 라이선스, [`LICENSE`](LICENSE) 참고.
- **공룡 스프라이트** (`src/main/resources/assets/dino_*.png`): [Chromium](https://www.chromium.org/)
  프로젝트 오프라인 페이지(`components/neterror`)의 스프라이트 시트에서 잘라낸 것으로,
  **BSD-3-Clause** 라이선스를 따릅니다. Copyright 2015 The Chromium Authors. 전체 고지는
  [`NOTICE`](NOTICE)를 참고하세요. 이 고지는 플러그인 안에도 `META-INF/NOTICE`로 포함되어 있습니다.
  PNG 파일은 수정 없이 그대로 배포됩니다. Chromium 200% 시트에는 T-Rex의 발 안쪽에 오프라인 페이지 배경색(`#F7F7F7`)
  픽셀이 몇 개 들어 있는데, 브라우저에서는 페이지 배경과 섞여 보이므로 플러그인은 정확히 그 픽셀들을 투명하게 그립니다.
- **운석 스프라이트** (`src/main/resources/assets/meteor_*.png`): 이 프로젝트를 위해 직접 만든 것이며,
  플러그인 코드와 동일한 MIT 라이선스로 배포됩니다.


# 민감한 값 스캔

API 키, 토큰, 비밀번호가 저장소에 올라가는 것을 세 계층에서 막는다.

| 계층 | 막는 지점 | 우회 |
|---|---|---|
| `.gitignore` | `.env*` 파일이 추적되는 것 | `git add -f` |
| pre-commit 훅 | 커밋 생성 | `--no-verify`, 훅 미활성화 |
| `Secret Scan` CI | develop push와 PR | 억제 설정을 같은 변경에 포함 |

`.gitignore`가 1차 방어선이고 훅과 CI는 그것을 뚫고 나간 경우를 잡는다.

## 로컬 훅 활성화

클론할 때마다 한 번 실행한다.

```bash
brew install gitleaks
git config core.hooksPath .githooks
```

`git config --get core.hooksPath`가 `.githooks`를 출력하면 활성화된 상태다.

`.githooks/`를 저장소에 커밋하고 `core.hooksPath`로 가리킨다. `.git/hooks/`는 버전 관리가 되지 않고 클론하면 사라지기 때문이다.

`core.hooksPath`를 설정하면 `.git/hooks/`의 훅은 모두 비활성화된다. 그쪽에 개인 훅을 두고 있었다면 `.githooks/`로 옮긴다.

gitleaks 바이너리는 [릴리스 페이지](https://github.com/gitleaks/gitleaks/releases)에서 받는다.

## 훅의 동작

`.githooks/pre-commit`은 스테이징된 변경만 스캔한다. 비정상 경로는 모두 차단으로 수렴한다. 검사하지 못한 상태를 통과로 처리하면 훅이 있다는 사실만 남는다.

| 상황 | 결과 |
|---|---|
| 민감한 값 없음 | 통과. 출력 없음 |
| 민감한 값 탐지 | 차단. `RuleID`, `File`, `Line`을 출력하고 값은 가린다 |
| gitleaks 미설치 또는 버전 하한 미달 | 차단. 설치와 업그레이드를 안내한다 |
| `.gitleaks.toml` 부재 또는 문법 오류 | 차단. 검사가 수행되지 않았음을 알린다 |
| `.gitleaksignore` 존재 | 차단. 억제 파일을 지우도록 안내한다 |

유출 탐지(2)와 실행 실패(1)는 gitleaks 종료 코드로 구분해 서로 다른 안내를 낸다. 훅 자체는 어느 쪽이든 1로 끝난다.

설정 파일은 `--config`로 절대 경로 고정한다. 고정하지 않으면 `GITLEAKS_CONFIG`와 `GITLEAKS_CONFIG_TOML` 환경변수로 느슨한 설정을 주입해 검사를 무력화할 수 있다.

## 버전

훅의 `MIN_VERSION`과 CI의 `GITLEAKS_VERSION`은 같은 값을 쓴다. 훅이 설치된 버전을 확인해 하한 미달이면 커밋을 막는다. 두 쪽이 다르면 로컬에서 통과한 커밋이 CI에서만 막히거나 그 반대가 된다.

버전을 올릴 때는 `.githooks/pre-commit`의 `MIN_VERSION`, `.github/workflows/secret_scan.yml`의 `GITLEAKS_VERSION`, 이 문서를 함께 고친다.

8.30 기준으로 스캔 하위 명령은 `dir`, `git`, `stdin` 세 개다. 오래된 예제가 쓰는 `gitleaks protect --staged`는 이 버전에서 사용법 오류로 끝난다.

## 규칙과 오탐 처리

`.gitleaks.toml`이 기본 규칙 세트를 상속하고 Toss 시크릿 키 규칙을 하나 더 얹는다.

`toss-secret-key` 규칙을 따로 두는 이유가 있다. 기본 `generic-api-key`는 값 근처의 키워드(`secret`, `key`, `api_key`)에 의존하므로 `String sk = "live_sk_…"`처럼 변수명이 축약어면 운영 시크릿 키도 탐지되지 않는다. 접두사만으로 잡도록 규칙을 추가했다.

허용은 규칙(`targetRules`)과 대상(`paths` 또는 `regexes`)을 함께 한정한다. 경로만 넓게 허용하면 그 경로에 들어간 진짜 민감한 값을 놓친다.

| 대상 | 적용 규칙 | 이유 |
|---|---|---|
| `src/test/java/**/Toss*Client*Test.java` | `generic-api-key` | 결제 클라이언트 테스트의 `paymentKey` 픽스처 |
| `src/test/resources/application-test.yml` | `generic-api-key` | 테스트 전용 JWT 시크릿과 스텁 키 |
| `test_ck_`, `test_gck_` 접두사 | `generic-api-key` | 브라우저로 내려가는 공개 값 |

클라이언트 키와 시크릿 키를 구분한다. 클라이언트 키(`ck`, `gck`)는 결제창을 띄우는 뷰가 브라우저에 전달하는 값이라 숨길 수 없고 main 경로에도 존재한다. 시크릿 키(`sk`, `gsk`)는 서버가 Basic 인증에 쓰는 테스트 상점의 실제 자격이므로 허용하지 않는다.

새 오탐은 한 줄에 국한되면 `gitleaks:allow` 주석으로, 재발하는 패턴이면 `[[allowlists]]`에 좁게 추가해 처리한다.

`--baseline-path`와 `.gitleaksignore`는 쓰지 않는다. 둘 다 fingerprint 단위로 탐지를 지우고 커밋 해시에 묶여 있어 히스토리를 재작성하면 억제가 풀린다. 훅과 CI 모두 `.gitleaksignore` 파일 존재를 거부한다.

`--gitleaks-ignore-path`로는 이 억제를 끌 수 없다. 8.30.1에서 이 플래그는 기본 탐색을 대체하지 않고 추가하므로 `/dev/null`을 줘도 저장소 루트의 `.gitleaksignore`가 그대로 적용된다. 그래서 플래그가 아니라 파일 존재를 거부한다.

## CI

`.github/workflows/secret_scan.yml`이 develop push와 PR에서 히스토리 전체를 스캔한다. `fetch-depth: 0`으로 전체 이력을 받고, 결과는 SARIF 아티팩트로 올린다. gitleaks 바이너리는 릴리스 체크섬을 검증한 뒤 설치한다.

배포는 `Build and Test with Gradle`의 성공을 `workflow_run`으로 받으므로 `Secret Scan` 실패가 배포를 막지 않는다. 머지를 막으려면 브랜치 룰셋에서 이 체크를 필수로 지정한다.

## 변경 후 검증

훅이나 설정을 수정하면 차단 동작을 실측한다.

판정은 종료 코드로 한다. gitleaks는 깨끗할 때 `no leaks found`를 출력하므로 `leaks found` 문자열을 세면 통과를 탐지로 잘못 읽는다.

positive control 문자열은 먼저 단독으로 검증한다. 형식만 비슷하게 만든 문자열은 탐지 대상이 아닐 수 있고, 그 경우 통과가 게이트 결함인지 문자열 오류인지 구별되지 않는다.

```bash
printf 'KEY = "<후보 문자열>"\n' > /tmp/probe.txt
gitleaks dir /tmp/probe.txt --no-banner --redact --exit-code 9
echo $?   # 9 면 control 로 쓸 수 있다
```

케이스마다 인덱스를 비운다. 프로브 파일이 남아 있으면 다음 케이스가 그것을 함께 스캔해 차단 사유가 바뀐다. 차단을 관측했을 때 출력의 `File`이 시험 대상과 같은지 확인한다.

```bash
F=raillo-api/src/main/java/com/sudo/raillo/TmpProbe.java
BEFORE=$(git rev-parse HEAD)
echo 'class TmpProbe { String T = "ghp_a1B2c3D4e5F6g7H8i9J0kLmNoPqRsTuVwXyZ"; }' > "$F"  # gitleaks:allow
git add "$F" && git commit -m "probe"
[ "$(git rev-parse HEAD)" = "$BEFORE" ] && echo "차단됨" || echo "통과됨 — 게이트 결함"
git rm --cached --force "$F" && rm -f "$F"
```

검증할 경로는 다음과 같다.

- 유출 탐지와 차단, 출력의 `File`과 `Line`
- 깨끗한 변경의 통과
- 허용 목록 밖 경로의 generic 시크릿 차단
- 허용된 파일 안의 공급자 토큰 차단
- 키워드 없는 `live_sk_` 형태 차단
- gitleaks 미설치, 버전 하한 미달, 설정 부재, 설정 문법 오류
- `GITLEAKS_CONFIG`와 `GITLEAKS_CONFIG_TOML` 주입, `.gitleaksignore` 생성

## 한계

세 계층 모두 자기 인증식 우회가 가능하다. 같은 변경에 `[[allowlists]]` 한 줄이나 `gitleaks:allow` 주석을 넣으면 훅과 CI가 그 억제를 존중한다. 억제 수단을 없애면 오탐을 처리할 길이 사라지므로 이 구조는 의도된 것이고, 최종 방어선은 코드 리뷰다. `.gitleaks.toml`이 변경된 PR은 그 변경 자체를 검토한다.

- 훅 활성화가 수동이라 활성화하지 않은 기여자에게는 적용되지 않는다.
- 기능 브랜치 push는 PR을 열기 전까지 스캔되지 않는다.
- `Secret Scan`을 필수 체크로 지정하기 전까지 머지를 막지 못한다.
- `.env*`는 `.env.example` 같은 템플릿도 무시한다. 템플릿을 커밋해야 하면 `!.env.example` 예외를 추가한다.

## GitHub Secret Protection

저장소 설정에서 Secret Protection과 Push protection을 켜면 push를 거부하는 계층이 하나 더 생긴다. 알려진 공급자 패턴만 탐지하고 개발자가 사유를 달아 우회할 수 있으므로 훅과 CI를 대체하지 않는다.

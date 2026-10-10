---
name: issue
description: 작업 내용을 받아 팀 컨벤션에 맞춘 GitHub 이슈를 작성하고, 사용자 승인 후 생성한다. Use when the user asks for an issue, like "이슈 작성해줘", "/issue FEATURE xxx", "버그 이슈 만들어줘".
argument-hint: "[CATEGORY] [작업 내용]"
allowed-tools: Bash(gh issue *) Bash(gh label list *)
---

# Issue

## 규칙

- **승인 전에는 생성하지 않는다.** 제목, 라벨, 본문을 먼저 보여주고 "올려줘", "생성해" 같은 명시적 승인 후에만 `gh issue create`를 실행한다.
- 제목은 `[CATEGORY] 설명`, 본문은 한국어 "-습니다" 완결형이다. 체크리스트 항목은 명사형으로 끝내도 된다.
- 할 일은 "~합니다", 이미 확인한 사실은 과거형 + 근거(환경, 이슈나 PR 번호)로 쓴다.
- 체크리스트에는 **테스트 항목과 문서 갱신 항목을 한 줄씩 반드시 둔다.** 없으면 "테스트 추가 없음: 이유", "문서 갱신 없음: 이유"로 쓴다.
  - 테스트 필요 여부: [.agents/rules/test.md](../../rules/test.md) "테스트가 반드시 있어야 하는 변경"
  - 갱신할 문서: [.agents/rules/docs.md](../../rules/docs.md) "변경에 따른 갱신 대상". 판단이 서지 않으면 추측하지 않고 묻는다.
- 웹 UI 템플릿을 쓰지 않는다. `--title`과 `--body`로 만들어야 아래 공통 형식이 유지된다.

## CATEGORY와 라벨

| CATEGORY | 라벨 |
|---|---|
| FEATURE | `✨ feature` |
| FIX | `🔧 fix` |
| REFACTOR | `♻️ refactor` |
| CHORE | `⚙️ chore` |
| TEST | `✅ test` |
| DOCS | `📝 docs` |

라벨명은 이모지까지 이름이다. `--label "✨ feature"`처럼 따옴표로 감싼다. 브랜치 라벨, 커밋 타입과의 매핑은 `/branch`가 단일 소스다.

## 본문 템플릿

헤더는 이모지까지 그대로 쓴다. FIX만 작업 설명과 체크리스트 사이에 재현 절차를 넣는다.

```markdown
### 📄 작업 설명
{1~3문장. 무엇을 왜 하는지. 버그면 현상과 기대 동작, 확인한 환경}

### 📈 재현 절차        ← FIX만
1. {단계}
2. 기대 결과와 실제 결과

### 📈 진행 체크리스트
- [ ] {구현 항목}
- [ ] {테스트 항목}
- [ ] {문서 갱신 항목}

### 👍🏻 추가 정보
{관련 이슈, PR, 코드 위치. 없으면 _No response_}
```

## 절차

1. CATEGORY를 정한다. 입력에 없으면 묻는다.
2. 본문을 채운다. 작업 항목이 입력에 없으면 관련 코드를 읽고 추측해 채우되, **무엇을 추측했는지 밝힌다.** 근거조차 없으면 계획을 묻는다.
3. 검수 요청을 보여주고 멈춘다.

   ```
   이슈 생성 전 검수 요청

   제목: [FEATURE] 열차 조회 잔여좌석 계산을 Redis 단일 소스로 전환
   라벨: ✨ feature

   본문:
   ---
   {본문}
   ---

   이대로 생성할까요?
   ```

4. 승인되면 생성하고 번호와 URL을 알린다.

   ```bash
   gh issue create --title "{제목}" --label "{라벨}" --body "$(cat <<'EOF'
   {본문}
   EOF
   )"
   ```

## 실패 처리

생성이 실패해도 본문은 그대로 두고 원인만 고친다.

| 오류 | 처리 |
|---|---|
| `could not add label` | `gh label list`로 정확한 라벨명을 확인해 재실행 |
| `authentication required` | 사용자에게 `gh auth login`을 요청 |

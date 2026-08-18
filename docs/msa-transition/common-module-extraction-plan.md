# Common Module Extraction Plan (Phase 0)

`raillo-backend-v2`를 MSA로 전환하기 위한 첫 단계로, 공유 라이브러리 코드를 Gradle multi-module `raillo-common`으로 분리한다. 이 문서는 [implementation-roadmap.md](./implementation-roadmap.md)의 Phase 0을 파일 단위 이관 목록·PR 순서·검증 방법까지 구체화한 실행 계획서다.

## 문서 관계

- [README.md](./README.md) — MSA 전환 macro 로드맵
- [common-module-strategy.md](./common-module-strategy.md) — 무엇을 공통 모듈에 담고 뺄지 원칙
- [implementation-roadmap.md](./implementation-roadmap.md) — Phase별 태스크 순서·의존·DoD
- **이 문서** — Phase 0 상세 실행 계획 (파일 단위 이관 목록, 5단계 PR, 검증 방법)
- 참고 서적 — 《모노리스에서 MSA로 전환하기》 1.3~1.4장 (전병선, 2026)

## 목표와 스코프

### 목표

1. Gradle multi-module 구조 도입: `raillo-common` (라이브러리) + `raillo-core` (모노리스 앱)
2. 여러 서비스가 공유할 코드를 `raillo-common`으로 이관
3. 모노리스 앱이 `raillo-common`을 의존해 기존 동작 그대로 유지

### 스코프 밖 (Phase 0에서 하지 않는 것)

- 서비스 물리 분리 (Auth·Payment 분리는 Phase 2·6)
- 이벤트/Outbox 인프라 (Phase 1)
- JWT 필터의 공통 모듈 승격 (Phase 2 Auth 분리 시점에 판단)
- 도메인 로직 리팩터

### Definition of Done (Phase 전체)

- `./gradlew clean build` 통과
- `./gradlew test` 통과 (Testcontainers MySQL/Redis 포함)
- `./gradlew :raillo-core:bootRun` 정상 기동
- 회귀 스모크 API 통과 (로그인 성공/실패, 예매 조회, 400 validation, 500 handler)
- `CLAUDE.md`(AGENTS.md)와 관련 문서 갱신

## 용어 정의

Gradle 모듈명이 비슷하게 들리므로 정의부터 명확히 한다.

- **`raillo-common`** — 공유 라이브러리 모듈. Spring Boot 실행 플러그인 미적용, jar만 빌드. 실행되지 않음. 여러 서비스가 함께 쓸 순수 형식/유틸만 담는다.
- **`raillo-core`** — 실행되는 모노리스 앱 모듈. 기존 `src/main`이 그대로 이 모듈로 이동한다. Spring Boot 실행 플러그인 적용, `bootRun`/`bootJar` 대상.
- **루트 (`raillo/`)** — 실행 앱이 아닌 컨테이너. `settings.gradle`이 두 모듈을 등록하고 `build.gradle`이 공통 설정을 제공한다.

## 모듈 구조

```
raillo/                              (root: 컨테이너, 실행 앱 X)
├── settings.gradle                   (rootProject.name = 'raillo', include 'raillo-common', 'raillo-core')
├── build.gradle                      (subprojects 공통 설정)
├── raillo-common/
│   ├── build.gradle                  (라이브러리 모듈, 부트 플러그인 X)
│   └── src/main/java/com/sudo/raillo/common/
│       ├── exception/                (ErrorCode, BusinessException 등)
│       ├── response/                 (SuccessResponse, ErrorResponse)
│       └── domain/                   (BaseEntity, YesNo)
└── raillo-core/
    ├── build.gradle                  (부트 앱, implementation project(':raillo-common'))
    ├── src/main/java/com/sudo/raillo/
    │   ├── auth/  booking/  member/  order/  payment/  train/
    │   └── global/                   (config 등 남는 인프라)
    ├── src/main/resources/           (application.yml, migration, static)
    └── src/test/                     (기존 테스트 통째)
```

### 루트 `build.gradle` 골자

```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.0' apply false
    id 'io.spring.dependency-management' version '1.1.7'
}

allprojects {
    group = 'com.sudo'
    version = '0.0.1-SNAPSHOT'
    repositories { mavenCentral() }
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

    dependencyManagement {
        imports { mavenBom "org.springframework.boot:spring-boot-dependencies:3.5.0" }
    }

    dependencies {
        compileOnly 'org.projectlombok:lombok'
        annotationProcessor 'org.projectlombok:lombok'
        testImplementation 'org.springframework.boot:spring-boot-starter-test'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    }

    test { useJUnitPlatform() }
}
```

- **`apply false`**: 루트는 실행되지 않으므로 부트 플러그인은 가져오되 적용하지 않음. 하위 모듈이 필요 시 적용.
- **BOM만 가져오기**: `raillo-common`도 이 BOM 덕분에 부트 라이브러리 버전이 자동 해결됨.

### `raillo-common/build.gradle`

```gradle
// 여러 서비스가 공유하는 순수 라이브러리 모듈 (Spring Boot 실행 플러그인 미적용).
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'       // ApiResponse/ErrorResponse가 참조하는 Spring MVC 타입
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'  // BaseEntity의 @MappedSuperclass·AuditingEntityListener
}

jar { enabled = true }
```

security 관련 의존성은 넣지 않는다. Auth의 JWT 필터/Provider는 이번 단계 스코프 밖(F 항목 참조)이라 common이 이걸 컴파일할 이유가 없다. 나중에 필요해지면 그 시점에 판단하고, 지금 미리 두면 스코프 결정과 코드가 어긋난다.

### `raillo-core/build.gradle`

기존 루트 `build.gradle` 내용에서 subprojects 공통 부분을 제외한 나머지를 이동한다.

```gradle
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

dependencies {
    implementation project(':raillo-common')

    // 기존 스타터
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    // ... 기존 의존성 그대로
}

// QueryDSL 설정 (경로는 raillo-core 하위로 재설정)
def querydslSrcDir = layout.buildDirectory.dir("generated/querydsl").get().asFile
sourceSets { main.java.srcDirs += [querydslSrcDir] }
tasks.withType(JavaCompile) {
    options.getGeneratedSourceOutputDirectory().set(file(querydslSrcDir))
}
clean { delete file(querydslSrcDir) }
```

## 이관 대상 파일 목록

현재 `src/main/java/com/sudo/raillo/global/` 아래 파일을 4개 카테고리로 분류한다.

### A. `raillo-common`으로 이관

| 현재 위치 | 신규 위치 |
|---|---|
| `global/exception/error/ErrorCode` | `common/exception/ErrorCode` |
| `global/exception/error/BusinessException` | `common/exception/BusinessException` |
| `global/exception/error/DomainException` | `common/exception/DomainException` |
| `global/exception/error/ExternalApiException` | `common/exception/ExternalApiException` |
| `global/exception/error/ErrorResponse` | `common/response/ErrorResponse` |
| `global/exception/error/GlobalError` | `common/exception/GlobalError` |
| `global/success/SuccessCode` | `common/response/SuccessCode` |
| `global/success/SuccessResponse` | `common/response/SuccessResponse` |
| `global/success/GlobalResponseHandler` | `common/response/GlobalResponseHandler` |
| `global/domain/BaseEntity` | `common/domain/BaseEntity` |
| `global/domain/YesNo` | `common/domain/YesNo` |
| `global/exception/GlobalExceptionHandler` (일부) | `common/exception/CommonExceptionHandler` (Step 3 참조) |

`YesNo`는 `GlobalError.INVALID_YN_VALUE`를 던지므로 `GlobalError`와 함께 이관해야 성립한다. 두 파일은 같은 PR로 옮긴다.

### B. `raillo-core`의 소유 도메인으로 이동

| 현재 위치 | 신규 위치 | 사유 |
|---|---|---|
| `global/redis/util/SeatHoldKeyGenerator` | `booking/infrastructure/` | 좌석 예매 전용 |
| `global/config/RedisScriptConfig` | `booking/infrastructure/config/` | 좌석 Lua 스크립트 Bean 등록 전용 |
| `global/config/AuthEmailConfig` | `auth/infrastructure/config/` | 인증 이메일 전용 |

### C. `raillo-core`에 유지 (공통 아님, 실행 앱 인프라)

- `global/config/CorsConfig`, `SecurityConfig`, `RedisConfig`, `CacheConfig`, `JpaAuditingConfig`, `QueryDslConfig`, `SwaggerConfig`, `WebConfig`, `ServerConfigLogger` — 자동설정은 실행 앱 소유
- `global/redis/exception/RedisError`, `RedisException` — Redis 유틸의 예외이므로 유틸과 함께 유지
- `global/redis/util/RedisKeyGenerator` — 좌석 외 다른 Redis 키에서도 쓰인다면 유지, 좌석 전용이면 booking으로 이동 (Step 4에서 결정)

### D. 삭제 또는 유예

| 파일 | 조치 |
|---|---|
| `global/exception/ErrorTestController` | 삭제 (개발 테스트용) |
| `global/exception/TestRequestDTO` | 삭제 (위 컨트롤러 전용) |
| `global/presentation/HealthController` | Actuator health와 중복 여부 확인 후 판단 |

### E. Phase 0 스코프 밖 (Phase 2로 유예)

Auth의 `security/jwt/`(JwtFilter·TokenExtractor·TokenValidator·TokenGenerator, JwtAuthenticationEntryPoint·JwtAccessDeniedHandler) — 책(1.4장)은 공통 모듈에 두라고 권장하지만 우리는 이번 Phase에서 옮기지 않는다. 근거는 다음과 같다.

**1. 책의 전제와 우리 구현의 차이**

책의 `JwtProvider`는 stateless — 서명·만료·클레임 파싱만 하는 순수 함수다.

```java
// 책 예제 (devjob-common)
public class JwtProvider {
    public boolean validateToken(String token) { /* 서명·만료 확인만 */ }
    public Long getMemberId(String token) { /* subject */ }
}
```

우리 구현은 stateful이다. Redis에 로그아웃 블랙리스트와 refresh 토큰을 두고 참조한다.

```
JwtFilter → TokenValidator → AuthRedisRepository (블랙리스트/로그아웃 상태 조회)
TokenGenerator → AuthRedisRepository (refresh 토큰 저장)
CustomUserDetailsService (회원 도메인 참조)
JwtAuthenticationEntryPoint, JwtAccessDeniedHandler
TokenError enum
```

검증만 뗀다는 것이 성립하지 않고, Auth 인프라를 함께 옮기게 된다.

**2. `raillo-common` 원칙 위반**

위 목록을 공통 모듈로 옮기면 common이 Redis·Auth·Member 도메인에 의존하게 된다. [common-module-strategy.md](./common-module-strategy.md)의 "도메인 로직 없음, 서비스별 비즈니스 로직 없음" 원칙과 정면 충돌한다.

**3. 현재 소비자가 1명 — 재사용 이득이 없다**

공통 모듈은 실제로 여러 서비스가 쓰는 것만 담는다. 지금은 Auth 도메인 하나가 `SecurityFilterChain`에 필터를 등록해 유일하게 소비한다. 두 번째 소비자가 없는 상태에서 미리 공유 라이브러리로 만드는 것은 "재사용을 위한 재사용" 함정이다 (책 1.4장의 "공통 모듈은 작게 유지하는 것이 미덕").

**4. 책의 공통 JWT 필터도 결국 은퇴한다**

책 1.4장 후반부는 이렇게 정리한다.

> 3장에서 게이트웨이가 인증을 앞단에서 한 번에 처리하게 되면, 각 서비스는 이 필터를 걷어내고 게이트웨이가 주입하는 `X-User-Id`를 신뢰한다. 검증의 책임이 게이트웨이로 넘어가면서 서비스 쪽 공통 JWT 필터는 은퇴한다.

즉 공통 JWT 필터는 잠깐 살다 사라진다. 우리 로드맵의 Phase 2 이후 Gateway 도입 시점에 어차피 재설계 대상이다.

**5. Phase 0 스코프 폭발 방지**

Phase 0을 예외·응답·BaseEntity에 국한하면 1주 내 완료. JWT까지 손대면 3~4주 규모로 커지고 회귀 리스크도 함께 커진다. 좁은 스코프 유지가 이 Phase의 성공 조건이다.

**6. Phase 2 시점의 판단 옵션**

Phase 2(Auth+Member 물리 분리)에서 두 갈래 중 선택한다.

- **A. JWT 검증을 Gateway로 이관** — 공통 모듈에 필터 불필요, Auth는 발급만 소유
- **B. 각 서비스가 여전히 검증** — stateless로 리팩터한 뒤 common으로 승격

어느 쪽이든 실제 요구사항이 드러난 시점에 결정하는 편이 정확하다.

**대안: 지금 옮기고 싶다면**

`JwtParser`만 common에 두는 최소 표면 이관은 가능하다. 서명·만료·클레임 추출만 담당하고, Redis 조회는 Auth에 남긴다. 다만 지금 소비자가 하나뿐이라 이 정도로도 재사용 이득이 미미하므로, 두 번째 소비자가 생길 때 함께 판단하는 편을 권장한다.

## GlobalExceptionHandler 분리

현재 `GlobalExceptionHandler`가 `auth.exception.TokenError`와 `redis.exception.RedisError`를 직접 import 하고 있어 공통 모듈로 그대로 승격할 수 없다. 세 조각으로 분리한다.

```
raillo-common/exception/CommonExceptionHandler       @Order(LOWEST_PRECEDENCE)
  - BusinessException, DomainException, ExternalApiException
  - MethodArgumentNotValidException, HttpMessageNotReadableException
  - MissingServletRequestParameterException, ConstraintViolationException
  - AccessDeniedException, IllegalArgumentException
  - Exception (최종 fallback)

raillo-core/auth/exception/AuthExceptionHandler       @Order(HIGHEST_PRECEDENCE)
  - BadCredentialsException → TokenError.INVALID_PASSWORD

raillo-core/global/redis/exception/RedisExceptionHandler   @Order(HIGHEST_PRECEDENCE)
  - RedisConnectionFailureException, SerializationException
  - InvalidDataAccessApiUsageException, RedisException
```

Spring은 등록된 모든 `@RestControllerAdvice`를 `@Order` 우선순위대로 순회하며 첫 매치를 사용한다. 도메인 handler에 `HIGHEST_PRECEDENCE`를 부여해 도메인 예외를 먼저 잡고, 남은 범용 예외를 `CommonExceptionHandler`가 처리하도록 한다.

## PR 5단계

각 Step은 독립 배포 가능한 단위로 마무리한다. 이전 Step 완료 없이 다음 Step 시작 금지.

### Step 1 — Multi-module 뼈대 (코드 변경 0)

**작업**
1. `settings.gradle`: `rootProject.name = 'raillo'`, `include 'raillo-common', 'raillo-core'`
2. 루트 `build.gradle`: subprojects 공통 설정 이동, 부트 플러그인 `apply false`
3. `raillo-common/build.gradle` 신규 (내용 최소; 이관은 다음 Step)
4. `raillo-core/build.gradle` 신규 (기존 build.gradle의 실행 앱 부분 이동, `implementation project(':raillo-common')` 추가)
5. `src/` 전체를 `raillo-core/src/`로 이동 (`git mv`로 히스토리 보존)
6. QueryDSL 생성 경로 `raillo-core/build/generated/querydsl`로 재설정
7. CI(`.github/workflows/*`)의 gradle 명령이 여전히 동작하는지 확인 (`./gradlew build`는 그대로)

**DoD**
- `./gradlew build` 통과
- `./gradlew :raillo-core:test` 통과
- `./gradlew :raillo-core:bootRun` 정상 기동
- **코드 라인 변경 0** (파일 이동만, import 변경 없음)

### Step 2 — 예외 계층 이관

**작업**
1. `global/exception/error/`의 `ErrorCode`, `BusinessException`, `DomainException`, `ExternalApiException`, `GlobalError`를 `raillo-common/src/main/java/com/sudo/raillo/common/exception/`으로 이동
2. 각 도메인의 error enum(`BookingError`, `PaymentError`, `AuthError`, `TokenError`, `RedisError` 등)은 위치 유지, `import com.sudo.raillo.common.exception.ErrorCode`로 변경
3. 각 도메인의 예외 사용처는 `import com.sudo.raillo.common.exception.BusinessException` 등으로 변경 (`find`/`sed` 스크립트로 일괄)
4. `raillo-core`의 `global/exception/error/` 폴더 삭제

**DoD**
- `./gradlew build` 통과
- 전체 테스트 통과
- 에러 응답 스키마 회귀 없음 (아래 회귀 API 확인)

### Step 3 — 응답 계층 + Handler 분리

**작업**
1. `global/exception/error/ErrorResponse` → `common/response/ErrorResponse`
2. `global/success/`의 `SuccessCode`, `SuccessResponse`, `GlobalResponseHandler` → `common/response/`
3. `global/exception/GlobalExceptionHandler`를 3개로 분리:
   - `common/exception/CommonExceptionHandler` (범용, `@Order(Ordered.LOWEST_PRECEDENCE)`)
   - `auth/exception/AuthExceptionHandler` (BadCredentials, `@Order(Ordered.HIGHEST_PRECEDENCE)`)
   - `global/redis/exception/RedisExceptionHandler` (Redis 계열, `@Order(Ordered.HIGHEST_PRECEDENCE)`)
4. 각 handler가 잡을 예외에 대한 통합 테스트 작성 (스키마 회귀 방지)

**DoD**
- `./gradlew build` 통과
- 통합 테스트 통과 (각 예외 → 응답 스키마 매핑 검증)
- curl 스모크:
  - 로그인 실패 → `TokenError.INVALID_PASSWORD` 응답
  - 400 validation → `GlobalError.INVALID_REQUEST_BODY` 응답
  - 예매 조회 성공 → `SuccessResponse` 래핑 확인

### Step 4 — `BaseEntity`·`YesNo` + 도메인 이동 + 정리

**작업**
1. `global/domain/BaseEntity` → `common/domain/BaseEntity` (모든 엔티티 import 변경)
2. `global/domain/YesNo` → `common/domain/YesNo` (`GlobalError.INVALID_YN_VALUE`는 Step 2에서 이관 완료)
3. 도메인 이동:
   - `global/redis/util/SeatHoldKeyGenerator` → `booking/infrastructure/`
   - `global/config/RedisScriptConfig` → `booking/infrastructure/config/`
   - `global/config/AuthEmailConfig` → `auth/infrastructure/config/`
4. 삭제: `global/exception/ErrorTestController`, `TestRequestDTO`
5. `HealthController`는 Actuator health와 중복이면 삭제, 아니면 유지 판단
6. `git grep "com.sudo.raillo.global."`으로 잔재 확인 (예상되지 않은 참조 없어야 함)

**DoD**
- `./gradlew build` 통과
- 전체 테스트 통과 (좌석 예약 관련 통합 테스트 특히 확인)
- `bootRun` 정상 기동, 좌석 Hold Lua 스크립트 로딩 확인

### Step 5 — 문서·컨벤션 반영

**작업**
1. `CLAUDE.md`(AGENTS.md):
   - 표준 패키지 구조에 `raillo-common` / `raillo-core` 이원 구조 반영
   - "공통 모듈에 담을 것 / 담지 말 것" 원칙 요약 링크
   - build/test 명령 예시 갱신 (`./gradlew :raillo-core:bootRun` 등)
2. `docs/msa-transition/common-module-strategy.md`:
   - 실제 이관한 파일 목록 추가
   - `GlobalExceptionHandler` 3분할 결정 기록
3. `docs/msa-transition/implementation-roadmap.md`:
   - Phase 0 상태를 "완료"로 변경
4. `docs/msa-transition/README.md`:
   - Phase 0 완료 표시, 다음 Phase 진입 조건 확인

**DoD**
- 새 도메인 개발 시 참조할 만한 상태
- 팀 온보딩용 안내가 갱신된 상태

## 검증 방법

### 각 Step 공통

1. `./gradlew clean build`
2. `./gradlew test`
3. `./gradlew :raillo-core:bootRun` (로컬 docker-compose 상태에서)
4. curl 스모크 API 목록 (모든 Step에서 동일):
   - 로그인 성공: `POST /api/auth/login` → 200 + `SuccessResponse` 래핑
   - 로그인 실패: `POST /api/auth/login` (틀린 비밀번호) → 401 + `TokenError.INVALID_PASSWORD`
   - 예매 조회: `GET /api/bookings/{id}` → 200 + `SuccessResponse<Booking>`
   - Validation 실패: `POST /api/*` (필수 필드 누락) → 400 + `GlobalError.INVALID_REQUEST_BODY`
   - 예상 예외: 좌석 Hold 시도 (Redis 정상) → 200

### Handler 분리 특별 검증 (Step 3)

각 `@RestControllerAdvice`가 예외를 예상대로 매칭하는지 통합 테스트로 검증:

- `AuthExceptionHandler`: `BadCredentialsException` 강제 → `TokenError.INVALID_PASSWORD` 응답
- `RedisExceptionHandler`: `RedisConnectionFailureException` mock → `RedisError.REDIS_CONNECT_FAIL` 응답
- `CommonExceptionHandler`: `BusinessException(BookingError.BOOKING_NOT_FOUND)` throw → 404 + BookingError 응답
- Fallback: 임의의 `NullPointerException` throw → 500 + `GlobalError.INTERNAL_SERVER_ERROR`

## 위험과 완화

| 위험 | 완화 |
|---|---|
| Multi-module 전환 시 IntelliJ 재인덱싱·QueryDSL Q클래스 재생성 실패 | Step 1에서 `sourceSets` 경로를 `raillo-core` 기준으로 재설정, `./gradlew :raillo-core:compileJava` 강제 실행으로 Q클래스 재생성 확인 |
| `@RestControllerAdvice` 우선순위 잘못 매칭 → 도메인 예외가 범용 handler로 잡힘 | `@Order` 명시(도메인=HIGHEST, common=LOWEST) + Step 3 통합 테스트로 각 예외→응답 매핑 회귀 검증 |
| ComponentScan 범위 문제로 Bean 누락 | `@SpringBootApplication`의 스캔 루트를 `com.sudo.raillo`로 유지 → `raillo-common`의 Bean도 자동 감지 |
| Flyway/Resource 경로 이동 회귀 | 리소스는 `raillo-core/src/main/resources/`에 유지 (common은 리소스 없음). Step 1의 `bootRun` 검증에서 확인 |
| CI(GitHub Actions)가 새 구조와 안 맞음 | Step 1에서 workflow의 gradle command 확인. `./gradlew build`는 그대로 통과 (multi-module 자동 전파) |
| `raillo-common`이 서서히 비대화 → "분산 모놀리스" 함정 | [common-module-strategy.md](./common-module-strategy.md)의 "담아도/담지 말 것" 원칙을 PR 리뷰 체크리스트로 명시. 애매하면 각 도메인에 두는 원칙 |
| Auth JWT 필터를 무리하게 이번에 이관하려는 유혹 | Phase 2 스코프임을 문서에 명시 (위 "스코프 밖" 절 참조). Phase 0은 원칙에 부합하는 것만 |

## 롤백 전략

각 Step 실패 시:

- **Step 1**: `git revert` 한 번으로 원상 복구. src 위치만 되돌리면 됨
- **Step 2·3**: 이관한 파일을 `git mv`로 원위치, import 변경분을 revert
- **Step 4**: 도메인 이동 파일을 원위치, ComponentScan은 영향 없음
- **Step 5**: 문서만 revert

프로덕션 배포 파이프라인은 여전히 단일 아티팩트(`raillo-core`의 bootJar)이므로 배포 관점에서는 영향 없음.

## 참고

- 《모노리스에서 MSA로 전환하기》 1.3 멀티 모듈 프로젝트, 1.4 공통 모듈 — 무엇을 담고 무엇을 뺄까
- [common-module-strategy.md](./common-module-strategy.md) — 공통 모듈 원칙
- [implementation-roadmap.md](./implementation-roadmap.md) — Phase별 순서
- [hexagonal-architecture.md](./hexagonal-architecture.md) — Phase 1 이후 신규 코드 구조

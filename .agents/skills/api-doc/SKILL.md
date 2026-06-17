---
name: api-doc
description: Controller에 대응하는 Swagger {Domain}ControllerDoc 인터페이스를 신규 생성합니다. Controller가 implements해서 사용. Use when the user asks like "/api-doc BookingController", "회원 API Swagger 문서 만들어줘".
---

# Swagger Docs Interface Generator

Controller의 모든 엔드포인트를 읽어 대응하는 `{Domain}ControllerDoc` 인터페이스를 생성한다. **신규 생성만 지원**한다. 기존 Controller 어노테이션 마이그레이션은 별도 요청 시에만.

## 위치 & 명명

```
src/main/java/com/sudo/raillo/{domain}/docs/{Domain}ControllerDoc.java
```

- 인터페이스명: `{Domain}ControllerDoc` (예: `BookingControllerDoc`, `TrainSearchControllerDoc`)
- 기존 도메인이 이미 이 컨벤션을 따른다 (모든 도메인이 `{domain}/docs/` 보유)

## 인터페이스 템플릿

```java
package com.sudo.raillo.{domain}.docs;

import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;
// 필요한 request/response DTO import
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "{Domain}s")
public interface {Domain}ControllerDoc {

    @Operation(
        method = "GET",
        summary = "{한국어 요약}",
        description = "{상세 설명}"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "{성공 메시지}"),
        @ApiResponse(
            responseCode = "400",
            description = "{실패 사유}",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "500",
            description = "서버 내부 오류가 발생했습니다.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    SuccessResponse<{ResponseDTO}> {methodName}(...);
}
```

## 절대 규칙

- **인터페이스에는 Swagger 어노테이션만**: `@Tag`, `@Operation`, `@ApiResponses`, `@ApiResponse`, `@Content`, `@Schema`, `@SecurityRequirement`, `@Parameter`
- **Controller에는 HTTP/바인딩 어노테이션만**: `@RestController`, `@RequestMapping`, `@GetMapping/@PostMapping/...`, `@RequestBody`, `@PathVariable`, `@RequestParam`, `@AuthenticationPrincipal`
- Controller는 `implements {Domain}ControllerDoc`로 인터페이스를 채택한다 (어노테이션 상속)
- **반환 타입**: 항상 `SuccessResponse<...>` (혹은 `SuccessResponse<?>` for void-like)
- **인증 필요 엔드포인트**: 인터페이스 메서드에 `@SecurityRequirement(name = "bearerAuth")` 추가
- **에러 응답 본문**: `@Content(schema = @Schema(implementation = ErrorResponse.class))`
- **Pageable 등 hidden 파라미터**: `@Parameter(description = "...", hidden = true)`

## Workflow

1. 대상 Controller 파일 읽기 (`{Domain}Controller.java`)
2. 메서드 시그니처와 사용된 DTO 추출:
   - HTTP 메서드, 경로, 파라미터, 반환 타입
   - `@AuthenticationPrincipal` 존재 → 인증 필요
3. 해당 도메인의 `docs/` 디렉토리 확인 → 없으면 생성
4. 인터페이스 생성:
   - 클래스 레벨 `@Tag` — 도메인 영문 복수형 (Bookings / Trains / Members / Payments / Tickets / Auth)
   - 각 메서드에 `@Operation` + `@ApiResponses`
   - 인증 필요 시 `@SecurityRequirement(name = "bearerAuth")`
   - 에러 응답은 `400`, `404`, `500` 중 적절한 것 + 도메인 특수 케이스
5. 컴파일 확인:
   ```bash
   ./gradlew compileJava
   ```
6. Controller가 아직 `implements`하지 않으면 추가 안내(직접 수정은 안 함):
   ```
   public class {Domain}Controller implements {Domain}ControllerDoc {
   ```

## 사용 예시

### 입력

```
/api-doc BookingController
```

### 처리

1. `src/main/java/com/sudo/raillo/booking/presentation/BookingController.java` 읽음
2. 메서드 3개 발견: `deleteBooking` (DELETE), `getBooking` (GET /{id}), `cancel` (PATCH /{id}/cancel)
3. `booking/docs/BookingControllerDoc.java` 생성

### 출력 인터페이스 (일부)

```java
@Tag(name = "Bookings")
public interface BookingControllerDoc {

    @Operation(method = "DELETE", summary = "예매 삭제",
        description = "사용자의 예매를 삭제합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "예매가 삭제되었습니다."),
        @ApiResponse(responseCode = "404", description = "예매를 찾을 수 없습니다.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    SuccessResponse<?> deleteBooking(BookingDeleteRequest request);

    @Operation(method = "GET", summary = "예매 상세 조회",
        description = "bookingId로 예매 상세 정보를 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "예매 상세 조회가 완료되었습니다."),
        @ApiResponse(responseCode = "404", description = "예매를 찾을 수 없습니다.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    SuccessResponse<BookingResponse> getBooking(Long bookingId);
}
```

## 출력 형식

```
✅ Swagger Docs 인터페이스 생성 완료

📂 파일: src/main/java/com/sudo/raillo/booking/docs/BookingControllerDoc.java
🆕 메서드 3개: deleteBooking, getBooking, cancel
🔐 인증 필요: deleteBooking, getBooking, cancel
🔨 컴파일: ./gradlew compileJava → BUILD SUCCESSFUL

💡 BookingController에 다음 변경이 필요합니다:
   public class BookingController implements BookingControllerDoc {
```

## 예외 처리

### Controller 파일을 찾을 수 없는 경우

```
⚠️ {Domain}Controller.java를 찾을 수 없습니다.

확인:
- src/main/java/com/sudo/raillo/{domain}/presentation/ 경로
- 클래스명 정확히 일치
```

### 도메인 docs 디렉토리에 이미 Doc 파일이 있는 경우

```
⚠️ {Domain}ControllerDoc.java가 이미 존재합니다.

옵션:
1. 기존 파일을 덮어쓸지 확인
2. 새 메서드만 추가 (기존 메서드와 비교 후 수동 머지 권장)
3. 이 skill은 신규 생성만 지원합니다. 기존 파일 수정은 직접 진행해주세요.
```

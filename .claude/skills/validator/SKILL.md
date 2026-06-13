---
name: validator
description: 도메인 ErrorCode를 사용하는 {Domain}Validator 클래스를 application/validator/ 아래에 생성하거나 메서드를 추가합니다. Use when the user asks like "/validator BookingValidator", "Payment 도메인에 validator 만들어줘", "PaymentValidator에 검증 메서드 추가".
---

# Domain Validator Generator

## 위치 & 명명

```
src/main/java/com/sudo/raillo/{domain}/application/validator/{Domain}Validator.java
```

- 클래스명: `{Domain}Validator` (예: `BookingValidator`, `PaymentValidator`)
- 기존 도메인: auth / booking / member / payment / train / order

## 클래스 템플릿

```java
package com.sudo.raillo.{domain}.application.validator;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.{domain}.exception.{Domain}Error;
import com.sudo.raillo.{domain}.infrastructure.{Repository};
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class {Domain}Validator {

    private final {Repository} {repository};

    /**
     * {목적}을 검증한다.
     *
     * @param ... 파라미터 설명
     * @throws BusinessException {언제 던지는지}
     */
    public void validate{What}(...) {
        if ({실패 조건}) {
            log.warn("{원인} - 파라미터: {}", ...);
            throw new BusinessException({Domain}Error.{ERROR_CODE});
        }
    }
}
```

## 절대 규칙

- **반환 타입은 항상 `void`** — 실패는 `throw new BusinessException(...)`으로 신호한다
- **의존성은 Repository만** 주입한다. 다른 Service/Facade/Validator 주입 금지 (Validator는 상태 없는 순수 검증)
- **호출처는 Service 계층** (Facade는 Service를 거쳐 호출)
- **로그 레벨**:
  - 사용자 입력/상태 위반: `log.warn`
  - 시스템 오류(예상 못한 데이터 불일치): `log.error`
- **메서드명**: `validateXxx` (예: `validateSeatCount`, `validatePaymentOwner`)
- **메서드별 JavaDoc**: 목적 + 발생 가능한 예외 명시
- **테스트**: 별도로 `/test {Domain}Validator` 호출. 이 skill은 테스트 생성하지 않는다

## Workflow

1. 사용자 입력에서 다음을 추출:
   - 도메인 (booking/payment/train/order/member/auth)
   - 검증 대상 (메서드 이름, 검증 내용)
2. 해당 도메인의 `exception/{Domain}Error.java` 위치 확인
   - 적합한 ErrorCode가 있으면 사용
   - 없으면 사용자에게 추가 안내 (직접 enum 항목 추가 권장)
3. Validator 클래스 존재 여부 확인
   - 없으면: 새 파일 생성 (위 템플릿)
   - 있으면: 메서드 추가
4. Repository 의존성이 필요하면 import 및 필드 추가
5. 컴파일 확인:
   ```bash
   ./gradlew compileJava
   ```
6. 결과 보고 (파일 경로, 추가된 메서드, 호출 예시)

## 사용 예시

### 예시 1: 신규 Validator 생성

입력:
```
/validator BookingValidator에 좌석 개수 검증 추가
```

처리:
- 위치: `booking/application/validator/BookingValidator.java`
- ErrorCode: `BookingError.PASSENGER_SEAT_MISMATCH` 사용
- 메서드 생성:

```java
/**
 * 승객 수와 선택한 좌석 수의 일치 여부를 검증한다.
 *
 * @throws BusinessException 승객 수와 좌석 수가 다를 때
 */
public void validateSeatCount(List<PassengerType> passengers, List<Long> seatIds) {
    if (passengers.size() != seatIds.size()) {
        log.warn("승객 수와 좌석 수 불일치 - 승객: {}, 좌석: {}", passengers.size(), seatIds.size());
        throw new BusinessException(BookingError.PASSENGER_SEAT_MISMATCH);
    }
}
```

### 예시 2: Repository 의존성 추가

입력:
```
/validator PaymentValidator에 결제 키 중복 검증 추가
```

처리:
- 클래스에 `PaymentRepository` 의존성 추가 (이미 있으면 재사용)
- 메서드:

```java
/**
 * paymentKey 중복 여부를 검증한다.
 */
public void validatePaymentKeyUnique(String paymentKey) {
    if (paymentRepository.existsByPaymentKey(paymentKey)) {
        log.warn("paymentKey 중복 - key: {}", paymentKey);
        throw new BusinessException(PaymentError.PAYMENT_KEY_DUPLICATED);
    }
}
```

## 출력 형식

```
✅ Validator 작업 완료

📂 파일: src/main/java/com/sudo/raillo/booking/application/validator/BookingValidator.java
🆕 추가된 메서드: validateSeatCount(List<PassengerType>, List<Long>)
🏷️ 사용 ErrorCode: BookingError.PASSENGER_SEAT_MISMATCH
🔨 컴파일: ./gradlew compileJava → BUILD SUCCESSFUL

💡 다음 단계: /test BookingValidator.validateSeatCount
```

## 예외 처리

### 도메인이 명확하지 않은 경우

```
⚠️ 어느 도메인의 validator인지 알려주세요.

기존 도메인: auth, booking, member, payment, train, order
```

### 적합한 ErrorCode가 없는 경우

```
⚠️ {Domain}Error enum에 적합한 항목이 없습니다.
다음 항목을 먼저 추가해주세요:

{ERROR_CODE}("{메시지}", HttpStatus.{STATUS}, "{prefix}_{번호}")

예: PASSENGER_SEAT_MISMATCH("승객 수와 좌석 수가 일치하지 않습니다.", HttpStatus.BAD_REQUEST, "B_007")
```

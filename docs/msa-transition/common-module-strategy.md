# Common Module Strategy

MSA 분리 시 여러 서비스가 반복해서 필요로 하는 코드(예외 계층, 응답 포맷, 공통 DTO, JWT 필터 등)를 어떻게 공유할지 정한다.

## 배경

각 서비스가 완전히 독립된 저장소·의존성을 가지면 다음이 반복된다.

- 에러 응답 포맷 (`{ code, message, timestamp, ... }`)
- 페이지네이션 DTO
- JWT 인증 필터·토큰 파싱 유틸
- 공통 예외 (`BusinessException`, `DomainException`, `ExternalApiException`)
- 도메인 이벤트 payload 스키마

반복 자체보다는 **에러 코드 포맷이나 이벤트 스키마가 서비스마다 미묘하게 달라져 클라이언트·소비자 통합이 어려워지는 것**이 진짜 리스크다.

## 원칙

공통 모듈은 도메인 중립적이고 안정적인 것만 담는다. 서비스별 비즈니스 로직이 유입되면 결합이 다시 강해지고, 공통 모듈 변경이 전 서비스 재배포를 유발한다.

**담아도 안전한 것**
- Base 예외 계층 (`BaseBusinessException`, `BaseDomainException`, `ErrorCode` 인터페이스)
- 공통 응답 래퍼 (`ApiResponse<T>`, `ErrorResponse`)
- 페이지네이션·정렬 DTO
- JWT 파싱·검증 유틸 (알고리즘·클레임이 표준화된 경우)
- 도메인 이벤트 payload 스키마 (`schemaVersion` 포함)

**담으면 안 되는 것**
- 도메인 엔티티나 서비스 로직
- 특정 서비스에만 쓰이는 상수·enum
- DB 접근 코드

## 예외 계층 예시

각 서비스는 공통 `ErrorCode` 인터페이스를 구현해 서비스별 에러를 정의한다.

```java
// common
public interface ErrorCode {
    String getCode();
    String getMessage();
    HttpStatus getHttpStatus();
}

public class BaseBusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    // ...
}
```

```java
// booking service
public enum BookingError implements ErrorCode {
    BOOKING_NOT_FOUND("BOOKING_101", "예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    // ...
}
```

이 프로젝트는 이미 모놀리스 내부에서 `ErrorCode` 인터페이스와 도메인별 enum 패턴을 사용한다 (`docs/error-code-convention.md`). MSA 분리 시 이 인터페이스를 공통 라이브러리로 승격하면 재사용 가능하다.

## 배포 방식

| 방식 | 장점 | 단점 |
|---|---|---|
| **Gradle multi-module** | 단일 저장소 유지, 리팩터 용이. 모놀리스 → MSA 초기에 유리. | 서비스별 배포 독립성이 저장소 구조에 종속. |
| **별도 저장소 + 아티팩트 배포** (Nexus/GitHub Packages) | 서비스 독립성 확보. 공통 모듈 버전 관리 명확. | 배포 파이프라인 추가, 버전 pin 관리 부담. |
| **Git submodule** | 저장소 참조 방식. | 버전 관리·CI 복잡도로 실무 채택 낮음. |

**판단**: 다음 순서로 진행한다 ([implementation-roadmap.md](./implementation-roadmap.md) Phase 0·2·6 참조).

1. **Phase 0**: 공통 예외·응답 래퍼·페이지네이션 DTO를 `common/` **Gradle multi-module로 추출**. Auth+Member 분리 이전에 완료해 후속 모든 Phase가 재사용.
2. **Phase 2 (Auth+Member 분리)**: JWT 유틸을 `common/`에 추가하거나 Auth 서비스에 유지 (판단 여지). 나머지 공통은 이미 준비된 상태.
3. **서비스 3개 이상 도달 시**: `common/` 모듈을 **별도 저장소로 승격**하고 아티팩트 배포(Nexus / GitHub Packages)로 전환.

## 리스크와 완화

- **공통 모듈 비대화**: "재사용" 명목의 코드가 계속 유입되면 결국 공유 모놀리스가 된다. PR 리뷰에서 도메인 로직 유입을 차단한다.
- **버전 스큐**: 서비스 A와 B가 서로 다른 공통 모듈 버전을 쓰면 이벤트 스키마 호환성 문제가 발생한다. 이벤트 payload는 `schemaVersion` 필드를 포함하고 소비자는 하위 호환성을 유지한다.
- **변경 파급**: 공통 예외 변경이 전 서비스 재배포를 유발한다. 변경 빈도가 낮은 것만 담는 원칙을 유지한다.

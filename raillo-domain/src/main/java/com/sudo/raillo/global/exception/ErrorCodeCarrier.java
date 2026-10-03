package com.sudo.raillo.global.exception;

/**
 * {@link ErrorCode}를 실어 던지는 예외가 구현한다.
 *
 * <p>{@code BusinessException}, {@code DomainException}, {@code RedisException}은 공통 부모가
 * {@code RuntimeException}뿐이어서, 에러 코드를 보려는 쪽이 세 클래스를 나열해야 했다. 나열은 새 예외 클래스가
 * 생길 때마다 조용히 빠뜨려지므로 들고 있는 속성으로 분기한다.</p>
 */
public interface ErrorCodeCarrier {

	ErrorCode getErrorCode();
}

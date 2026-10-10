package com.sudo.raillo.payment.application.required;

import com.sudo.raillo.payment.application.BookingSeatReleasePayload;

/** 예매가 삭제된 뒤 Redis 예매 점유를 해제하는 required port. */
public interface BookedSeatReleaser {

	void release(BookingSeatReleasePayload payload);
}

package com.sudo.raillo.order.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

	private final OrderRepository orderRepository;

	public Order getOrderByOrderCode(String orderCode) {
		return orderRepository.findByOrderCode(orderCode)
			.orElseThrow(() -> new BusinessException(OrderError.ORDER_NOT_FOUND));
	}

	public void validateOrderOwner(Order order, Member member) {
		if (!order.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Order의 소유자가 아님: orderCode={}, requestMemberId={}, orderMemberId={}",
				order.getOrderCode(), member.getId(), order.getMember().getId());
			throw new BusinessException(OrderError.ORDER_ACCESS_DENIED);
		}
	}
}

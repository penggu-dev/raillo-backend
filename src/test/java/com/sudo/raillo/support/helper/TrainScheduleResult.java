package com.sudo.raillo.support.helper;

import java.util.List;

import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;

/**
 * 열차 스케줄과 정차역 정보를 함께 담는 테스트용 결과 객체
 *
 * <p>{@link TrainScheduleTestHelper}에서 스케줄 생성 시 반환되며,
 * 생성된 {@link TrainSchedule}과 해당 스케줄에 속한 {@link ScheduleStop} 목록을 함께 제공한다.</p>
 *
 * <h4>사용 예시</h4>
 * <pre>{@code
 * ScheduleWithStops result = trainScheduleTestHelper.createSchedule(train);
 *
 * TrainSchedule schedule = result.trainSchedule();
 * List<ScheduleStop> stops = result.scheduleStops();
 * }</pre>
 *
 * @param trainSchedule 생성된 열차 스케줄
 * @param scheduleStops 해당 스케줄의 정차역 목록 (정차 순서대로 정렬)
 */
public record TrainScheduleResult(TrainSchedule trainSchedule, List<ScheduleStop> scheduleStops) {
}

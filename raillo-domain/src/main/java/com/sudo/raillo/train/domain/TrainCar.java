package com.sudo.raillo.train.domain;

import com.sudo.raillo.train.domain.type.CarType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainCar {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "train_car_id")
	private Long id;

	private int carNumber;

	@Enumerated(EnumType.STRING)
	private CarType carType;

	private int seatRowCount;

	private int totalSeats;

	@Comment("2+2, 2+1")
	private String seatArrangement;

	@Setter
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "train_id")
	private Train train;

	private TrainCar(int carNumber, CarType carType, int seatRowCount, int totalSeats,
		String seatArrangement, Train train) {
		this.carNumber = carNumber;
		this.carType = carType;
		this.seatRowCount = seatRowCount;
		this.totalSeats = totalSeats;
		this.seatArrangement = seatArrangement;
		this.train = train;
	}

	public static TrainCar create(int carNumber, CarType carType, int seatRowCount, int totalSeats,
		String seatArrangement, Train train) {
		return new TrainCar(carNumber, carType, seatRowCount, totalSeats, seatArrangement, train);
	}
}

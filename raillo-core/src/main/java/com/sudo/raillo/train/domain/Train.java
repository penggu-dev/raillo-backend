package com.sudo.raillo.train.domain;

import com.sudo.raillo.train.domain.type.TrainType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Train {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "train_id")
	private Long id;

	private int trainNumber;

	@Enumerated(EnumType.STRING)
	private TrainType trainType;

	@Comment("KTX, KTX-산천, ITX-청춘 등")
	private String trainName;

	private int totalCars;
}

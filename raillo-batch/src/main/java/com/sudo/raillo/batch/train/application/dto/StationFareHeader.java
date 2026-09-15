package com.sudo.raillo.batch.train.application.dto;

import org.apache.poi.ss.util.CellAddress;

public record StationFareHeader(
	int startRow,
	CellAddress section,
	CellAddress standard,
	CellAddress firstClass
) {
}

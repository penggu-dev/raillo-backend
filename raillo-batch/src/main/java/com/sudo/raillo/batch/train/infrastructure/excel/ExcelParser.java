package com.sudo.raillo.batch.train.infrastructure.excel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.Resource;
import org.springframework.util.ObjectUtils;

abstract class ExcelParser {

	protected abstract Resource getResource();

	protected List<String> getExcludeSheetNames() {
		return List.of();
	}

	public List<Sheet> getSheets() {
		List<Sheet> sheets = new ArrayList<>();
		Resource resource = getResource();
		try (InputStream stream = resource.getInputStream();
			Workbook workbook = WorkbookFactory.create(stream)) {
			for (Sheet sheet : workbook) {
				boolean isExcluded = getExcludeSheetNames().stream()
					.anyMatch(excludeName -> sheet.getSheetName().contains(excludeName));

				if (!isExcluded) {
					sheets.add(sheet);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("파일을 읽을 수 없습니다: " + resource.getDescription(), ex);
		}
		return sheets;
	}

	protected boolean isEmpty(Row row, int cellNum) {
		if (ObjectUtils.isEmpty(row)) {
			return true;
		}

		Cell cell = row.getCell(cellNum, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
		return ObjectUtils.isEmpty(cell);
	}
}

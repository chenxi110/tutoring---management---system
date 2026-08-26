package com.skt.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

public class ExcelExportUtil {
    public static byte[] export(String[] headers, String[] keys, List<Map<String, Object>> dataList, String sheetName) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName != null ? sheetName : "Sheet1");
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 15 * 256);
            }
            if (dataList != null) {
                for (int rowIndex = 0; rowIndex < dataList.size(); rowIndex++) {
                    Row dataRow = sheet.createRow(rowIndex + 1);
                    Map<String, Object> rowData = dataList.get(rowIndex);
                    for (int colIndex = 0; colIndex < keys.length; colIndex++) {
                        Cell cell = dataRow.createCell(colIndex);
                        Object value = rowData.get(keys[colIndex]);
                        cell.setCellValue(value != null ? value.toString() : "");
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Excel导出失败: " + e.getMessage(), e);
        }
    }
}

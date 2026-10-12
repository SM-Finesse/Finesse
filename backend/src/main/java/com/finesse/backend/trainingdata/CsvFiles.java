package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 학습 데이터 CSV 읽기·쓰기 — RFC 4180 인용 규칙, UTF-8 BOM(엑셀 한글), 줄바꿈 LF (학습 데이터 CSV 구조 지시서 샘플과 같은 형식).
 */
public final class CsvFiles {

    public static final String BOM = "﻿";

    private CsvFiles() {
    }

    /** 쉼표·큰따옴표·줄바꿈이 있으면 큰따옴표로 감싸고 안의 큰따옴표는 두 번 쓴다. null은 빈 칸 */
    public static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 한 행 — 끝에 LF를 붙인다 */
    public static String row(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(cells.get(i)));
        }
        return sb.append('\n').toString();
    }

    public static List<List<String>> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 앞의 BOM을 떼고 행으로 나눈다. 인용 안의 쉼표·줄바꿈은 값으로 본다. 빈 줄은 건너뛴다 */
    public static List<List<String>> parse(String text) {
        String s = text.startsWith(BOM) ? text.substring(1) : text;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean rowHasContent = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                rowHasContent = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                rowHasContent = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                if (rowHasContent || cell.length() > 0) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                rowHasContent = false;
            } else {
                cell.append(c);
                rowHasContent = true;
            }
        }
        if (rowHasContent || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}

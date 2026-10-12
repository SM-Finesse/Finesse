package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * user_map.csv — user_key ↔ username (🔴 레포 밖, 닉네임이 들어 있다). 작업지시서 2절.
 * targets.csv 순서대로 u0001부터 발급하고, 이미 있는 이름은 같은 키를 다시 쓴다 — 재시작해도 키가 바뀌지 않는다.
 */
public final class UserKeyRegistry {

    static final String HEADER = "user_key,username\n";

    private final Path file;
    private final Map<String, String> keyByName = new LinkedHashMap<>();
    private int lastNumber;

    private UserKeyRegistry(Path file) {
        this.file = file;
    }

    public static UserKeyRegistry load(Path file) throws IOException {
        UserKeyRegistry registry = new UserKeyRegistry(file);
        DurableFiles.dropPartialLastLine(file); // 기록 중 죽어 끊긴 줄은 버린다 — 그 이름은 아래 assignAll에서 다시 발급
        if (Files.exists(file)) {
            List<List<String>> rows = CsvFiles.read(file);
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.size() < 2) {
                    continue;
                }
                registry.keyByName.putIfAbsent(row.get(1), row.get(0));
                registry.lastNumber = Math.max(registry.lastNumber, number(row.get(0)));
            }
        }
        return registry;
    }

    /** 처음 보는 이름에 키를 발급해 한 번에 기록하고, names 전체의 키를 돌려준다 */
    public Map<String, String> assignAll(List<String> names) throws IOException {
        StringBuilder added = new StringBuilder();
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            String key = keyByName.get(name);
            if (key == null) {
                key = String.format("u%04d", ++lastNumber);
                keyByName.put(name, key);
                added.append(CsvFiles.row(List.of(key, name)));
            }
            result.put(name, key);
        }
        if (!added.isEmpty()) {
            DurableFiles.append(file, HEADER, added.toString());
        }
        return result;
    }

    private static int number(String key) {
        try {
            return Integer.parseInt(key.substring(1));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

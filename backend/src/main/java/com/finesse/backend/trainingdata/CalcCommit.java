package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * calc_commit — 수집에 쓴 코드의 커밋 해시 앞 7자 (작업지시서 1.3절). 어느 버전 공식으로 계산했는지 답할 수 있어야 한다.
 * 실행 스크립트가 수집 직전에 jar를 다시 빌드하므로 HEAD와 실행 코드가 같다.
 */
public final class CalcCommit {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{7}$");

    private CalcCommit() {
    }

    /**
     * @param configured collector.calc-commit — 있으면 그대로 쓴다(git이 없는 PC용)
     * @throws IllegalStateException git으로 읽지 못했거나, 커밋 안 된 코드 변경이 있는데 allowDirty가 아니면
     */
    public static String resolve(String configured, boolean allowDirty, Path workDir) {
        if (configured != null && !configured.isBlank()) {
            String value = configured.trim().toLowerCase();
            if (!HASH.matcher(value).matches()) {
                throw new IllegalStateException("collector.calc-commit은 커밋 해시 앞 7자여야 합니다: " + configured);
            }
            return value;
        }
        String hash = git(workDir, "rev-parse", "--short=7", "HEAD");
        if (hash == null || !HASH.matcher(hash).matches()) {
            throw new IllegalStateException("git으로 커밋 해시를 읽지 못했습니다 — 레포 안에서 실행하거나 "
                    + "--collector.calc-commit=xxxxxxx 로 지정하세요");
        }
        String changes = git(workDir, "status", "--porcelain", "--untracked-files=no");
        if (changes != null && !changes.isBlank() && !allowDirty) {
            throw new IllegalStateException("커밋 안 된 코드 변경이 있어 calc_commit(" + hash + ")이 실행 코드와 맞지 않습니다 — "
                    + "커밋하거나 되돌린 뒤 실행하세요(무시하려면 --collector.allow-dirty=true)");
        }
        return hash;
    }

    private static String git(Path workDir, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

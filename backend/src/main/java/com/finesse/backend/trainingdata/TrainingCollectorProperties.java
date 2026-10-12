package com.finesse.backend.trainingdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/**
 * collector 프로파일 설정 (application-collector.yml).
 *
 * @param dataDir                저장 위치 — targets.csv·user_map.csv·users.csv·collected/·logs/. 🔴 레포 밖.
 *                               복원 프로그램이 지우지 않는 곳으로 정한다(작업지시서 4.8절, DevOps 조사 후 확정)
 * @param targetsFile            수집 대상 명단(username 한 열)
 * @param intervalSeconds        직전 수집이 끝난 뒤 다음 수집까지 대기 — 고정 스케줄이 아니다(4.2절)
 * @param rateLimitedIntervalSeconds 429(rate_limited) 뒤 대기 — 1분 → 5분(4.6절). 다음 수집이 성공하면 다시 intervalSeconds
 * @param maxConsecutiveFailures 연속 실패가 이 횟수가 되면 중단(4.6절)
 * @param calcCommit             수집에 쓴 코드 커밋 7자리 — 비우면 git에서 읽는다(1.3절)
 * @param allowDirty             커밋 안 된 코드 변경이 있어도 수집할지 — 기본은 거부(calc_commit이 코드와 안 맞게 되므로)
 */
@Profile("collector")
@ConfigurationProperties(prefix = "collector")
public record TrainingCollectorProperties(
        Path dataDir,
        Path targetsFile,
        @DefaultValue("60") int intervalSeconds,
        @DefaultValue("300") int rateLimitedIntervalSeconds,
        @DefaultValue("3") int maxConsecutiveFailures,
        String calcCommit,
        @DefaultValue("false") boolean allowDirty
) {
}

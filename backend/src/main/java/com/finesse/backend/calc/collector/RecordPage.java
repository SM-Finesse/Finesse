package com.finesse.backend.calc.collector;

import java.util.List;

/**
 * GET /users/{username}/records/league/recent 한 페이지 (설계서 3.4절).
 * matches는 응답 순서(최신순) 그대로이며, 파싱에 실패한 레코드는 droppedRecords로 센다.
 * nextCursor는 마지막 레코드의 p(pri:sec:ter)이며, 없으면 null(마지막 페이지).
 */
public record RecordPage(
        List<RawMatch> matches,
        int droppedRecords,
        String nextCursor
) {
    public RecordPage {
        matches = List.copyOf(matches);
    }
}
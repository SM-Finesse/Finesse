package com.finesse.backend.queue;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 대기표 IP 제한에 쓰는 사용자 IP — 「전적 검색 대기열 문서」 5.5절.
 * nginx가 Cloudflare의 CF-Connecting-IP를 X-Real-IP로 덮어써 넘긴다(nginx PR은 QA 진행). X-Forwarded-For는
 * 왼쪽 값을 사용자가 직접 넣을 수 있어 쓰지 않는다. X-Real-IP가 없으면(로컬 직접 호출) 연결 주소.
 * nginx 변경 전에는 터널을 거친 모든 사용자가 cloudflared 주소 하나로 보이므로 IP 제한을 켜지 않는다(max-tickets-per-ip: 0).
 */
public final class ClientIp {

    public static final String HEADER = "X-Real-IP";

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String real = request.getHeader(HEADER);
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}

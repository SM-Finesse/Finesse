package com.finesse.backend.queue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 대기표 IP 제한의 IP — X-Real-IP 기준, X-Forwarded-For는 보지 않는다(「전적 검색 대기열 문서」 5.5절, Q17) */
class ClientIpTest {

    @Test
    void Q17_X_Forwarded_For를_위조해도_X_Real_IP를_쓴다() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("172.18.0.5"); // cloudflared → nginx 연결 주소
        req.addHeader("X-Real-IP", "203.0.113.7"); // nginx가 CF-Connecting-IP로 덮어쓴 값
        req.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7"); // 왼쪽은 사용자가 넣을 수 있음
        assertThat(ClientIp.of(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void X_Real_IP가_없으면_연결_주소_X_Forwarded_For는_무시() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "198.51.100.99");
        assertThat(ClientIp.of(req)).isEqualTo("127.0.0.1");
    }
}

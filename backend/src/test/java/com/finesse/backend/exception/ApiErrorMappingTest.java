package com.finesse.backend.exception;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.controller.CommentController;
import com.finesse.backend.controller.StatsController;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import com.finesse.backend.service.CommentService;
import com.finesse.backend.service.StatsService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 오류 응답이 공통 포맷({error_code, message})으로 나가는지 — 내부 정보(스택트레이스)가 새지 않게.
 */
class ApiErrorMappingTest {

    private MockMvc mvc(CommentService commentService) {
        return MockMvcBuilders.standaloneSetup(new CommentController(commentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 필수_파라미터_scope가_없으면_공통_포맷_400() throws Exception {
        mvc(mock(CommentService.class)).perform(get("/api/v1/comment/icly"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BAD_REQUEST")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("trace"))));
    }

    @Test
    void EventSource처럼_Accept가_이벤트_스트림이어도_잘못된_유저명은_JSON_400() throws Exception {
        CommentService service = mock(CommentService.class);
        when(service.getHeavyStream(anyString())).thenThrow(new IllegalArgumentException("유저명 형식 오류"));

        mvc(service).perform(get("/api/v1/comment/a b").param("scope", "heavy")
                        .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BAD_REQUEST")));
    }

    @Test
    void heavy_오류_이벤트는_내부_메시지_없이_사용자용_문구와_재시도_시간을_보낸다() {
        var busy = com.finesse.backend.service.CommentServiceTestAccess.statsFailureBody(
                new ServerBusyException("stats 수집 동시 처리 상한 초과: hahi", 5));
        org.assertj.core.api.Assertions.assertThat(busy.errorCode()).isEqualTo("SERVER_BUSY");
        org.assertj.core.api.Assertions.assertThat(busy.message()).isEqualTo(GlobalExceptionHandler.SERVER_BUSY_MESSAGE);
        org.assertj.core.api.Assertions.assertThat(busy.retryAfterSeconds()).isEqualTo(5);

        var tetrio = com.finesse.backend.service.CommentServiceTestAccess.statsFailureBody(
                new TetrioApiException("TETR.IO 수집 실패(FAILED): hahi", null));
        org.assertj.core.api.Assertions.assertThat(tetrio.message()).isEqualTo(GlobalExceptionHandler.TETRIO_UNAVAILABLE_MESSAGE);
        org.assertj.core.api.Assertions.assertThat(tetrio.retryAfterSeconds()).isNull();
    }

    @Test
    void HTTP_503_SERVER_BUSY도_본문에_재시도_시간을_싣는다() {
        var response = new GlobalExceptionHandler().handleServerBusy(new ServerBusyException("내부", 5));
        org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        org.assertj.core.api.Assertions.assertThat(response.getBody().retryAfterSeconds()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(response.getBody().message()).doesNotContain("내부");
    }

    @Test
    void 예상하지_못한_오류는_내부_정보_없이_500() throws Exception {
        CommentService service = mock(CommentService.class);
        when(service.getLight(anyString())).thenThrow(new IllegalStateException("내부 상태 노출되면 안 됨"));

        mvc(service).perform(get("/api/v1/comment/icly").param("scope", "light"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INTERNAL_ERROR")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("내부 상태"))));
    }

    @Test
    void 파라미터_형식이_틀리면_500이_아니라_400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StatsController(mock(StatsService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/stats/icly").param("refresh", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BAD_REQUEST")));
    }

    @Test
    void 상태_코드가_정해진_Spring_예외는_그_상태로_공통_포맷() throws Exception {
        // 예: 지원하지 않는 메서드(405) — 기본 처리로 넘기면 개발 모드에서 스택트레이스가 본문에 실린다
        var response = new GlobalExceptionHandler().handleUnexpected(new HttpRequestMethodNotSupportedException("POST"));
        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(405);
        org.assertj.core.api.Assertions.assertThat(response.getBody().errorCode()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void light_코멘트_안에서_난_stats_오류는_감싸지지_않고_원래_예외로_올라온다() {
        // 캐시 로더 안에서 난 예외는 Cache.ValueRetrievalException으로 감싸진다 — 풀지 않으면 공통 500에 걸린다
        StatsService stats = mock(StatsService.class);
        when(stats.getStats(anyString(), anyBoolean())).thenThrow(new UserNotFoundException("nobody"));
        CommentService service = new CommentService(stats, mock(LlmClient.class), new ConcurrentMapCacheManager(),
                mock(ExecutorService.class), mock(LlmProperties.class), mock(EndpointProperties.class));

        assertThatThrownBy(() -> service.getLight("nobody")).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void 규칙에_안_맞는_유저명은_TETR_IO를_부르지_않고_400() {
        StatsService stats = mock(StatsService.class);
        CommentService service = new CommentService(stats, mock(LlmClient.class), new ConcurrentMapCacheManager(),
                mock(ExecutorService.class), mock(LlmProperties.class), mock(EndpointProperties.class));

        assertThatThrownBy(() -> service.getLight("a b")).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(stats);
    }
}

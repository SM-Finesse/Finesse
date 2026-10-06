package com.finesse.backend.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * 외부 호출용 WebClient 빈 — Finesse-API명세서 4.1-1절(TETR.IO), 6장(LLM 서버).
 * LLM은 서버가 여러 대(라운드로빈 대상)라 baseUrl을 고정하지 않고 Builder만 제공한다.
 * 실제 라운드로빈 선택 로직은 LlmClient 쪽에서 LlmProperties.servers() 를 순회하며 구현한다 (6.1절).
 */
@Configuration
public class WebClientConfig {

    // records/league/recent?limit=100 응답은 라운드별 상세 데이터까지 포함해 기본 버퍼 한도(256KB)를 넘는다
    // (실측: turtle 100판 응답에서 DataBufferLimitException 발생) — 10MB로 넉넉히 올려둔다.
    private static final int MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024;

    // 주의: WebClient.builder()가 기본으로 쓰는 Jackson 코덱은 spring.jackson.property-naming-strategy
    // (SNAKE_CASE) 가 반영되지 않은 "맨 기본" JsonMapper를 쓴다. 그 결과 light_summary/chapter_id 같은
    // snake_case 응답 필드가 전부 null로 바인딩되는 버그가 있었다 — 앱이 실제로 쓰는(자동설정된,
    // SNAKE_CASE 적용된) JsonMapper 빈을 주입받아 인코더/디코더에 명시적으로 꽂아서 고쳤다.
    private ExchangeStrategies jacksonAwareStrategies(JsonMapper jsonMapper) {
        return ExchangeStrategies.builder()
                .codecs(c -> {
                    c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE);
                    c.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    c.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .build();
    }

    @Bean
    public WebClient tetrioWebClient(JsonMapper jsonMapper, TetrioProperties tetrioProperties) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(tetrioProperties.requestTimeoutSeconds()));
        return WebClient.builder()
                .baseUrl(tetrioProperties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(jacksonAwareStrategies(jsonMapper))
                .build();
    }

    @Bean
    public WebClient.Builder llmWebClientBuilder(JsonMapper jsonMapper, LlmProperties llmProperties) {
        // 전송 계층 responseTimeout은 LlmClient가 요청마다 거는 실제 타임아웃(light 15s/heavy 10s)보다
        // 짧으면 안 되므로 둘 중 큰 값으로 넉넉히 잡는다 — 실제 컷오프는 LlmClient의 .timeout()이 담당.
        int transportTimeout = Math.max(llmProperties.lightCallTimeoutSeconds(), llmProperties.heavyCallTimeoutSeconds());
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, llmProperties.connectTimeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(transportTimeout));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(jacksonAwareStrategies(jsonMapper));
    }
}

package com.finesse.backend.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI 상단에 표시되는 API 문서 정보.
 * 화면: /swagger-ui.html, 원본 스펙(JSON): /v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI finesseOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Finesse Backend API")
                .version("v1")
                .description("TETR.IO 유저 통계 + AI 코멘트 API. 상세 규칙은 API 명세서(백엔드 설계) 4장, "
                        + "타임아웃은 타임아웃 기준 정리 문서(23번) 기준."));
    }

    // swagger-core는 Jackson 2(com.fasterxml)로 스키마를 만들고, 앱은 Jackson 3(tools.jackson)로 응답을 직렬화한다.
    // application.yml의 SNAKE_CASE 설정이 swagger 쪽에는 안 넘어가서, 여기서 같은 규칙을 따로 지정해 둔다.
    @Bean
    public ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json.mapper().copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}

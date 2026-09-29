package com.finesse.backend.calc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FinesseAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinesseAnalyticsApplication.class, args);
    }

}

package com.biat.t24.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class T24AssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(T24AssistantApplication.class, args);
    }
}

package com.waygo.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class WaygoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaygoBackendApplication.class, args);
    }

}

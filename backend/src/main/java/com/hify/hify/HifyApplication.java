package com.hify.hify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 这是整个后端的"大门"。@SpringBootApplication 让 Spring 自动扫描本包及子包下的所有组件。
@SpringBootApplication
public class HifyApplication {
    public static void main(String[] args) {
        SpringApplication.run(HifyApplication.class, args);
    }
}

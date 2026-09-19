package com.rag.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.rag.worker.infrastructure.persistence.mapper")
public class RagWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagWorkerApplication.class, args);
    }
}

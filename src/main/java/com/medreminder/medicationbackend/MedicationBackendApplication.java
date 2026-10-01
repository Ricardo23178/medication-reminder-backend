package com.medreminder.medicationbackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.medreminder.medicationbackend.mapper")
public class MedicationBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicationBackendApplication.class, args);
    }


}

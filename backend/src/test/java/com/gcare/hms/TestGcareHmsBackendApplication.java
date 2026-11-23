package com.gcare.hms;

import org.springframework.boot.SpringApplication;

public class TestGcareHmsBackendApplication {

  public static void main(String[] args) {
    SpringApplication.from(GcareHmsBackendApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}

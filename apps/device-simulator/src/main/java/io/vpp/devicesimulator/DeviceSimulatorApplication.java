package io.vpp.devicesimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeviceSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceSimulatorApplication.class, args);
    }
}

package dev.hotshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HotShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotShieldApplication.class, args);
    }
}

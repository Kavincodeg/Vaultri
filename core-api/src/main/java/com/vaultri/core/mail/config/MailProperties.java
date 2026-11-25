package com.vaultri.core.mail.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vaultri.mail")
public class MailProperties {
    private String host;
    private Integer port = 587;
    private String username;
    private String password;
    private String from;
    private Integer connectionTimeout = 5000;
    private Integer timeout = 10000;
    private Integer writeTimeout = 10000;

    private final Environment environment;

    public MailProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd) {
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("Production profile requires vaultri.mail.host to be configured.");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("Production profile requires vaultri.mail.username to be configured.");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Production profile requires vaultri.mail.password to be configured.");
            }
        }
    }

    @Override
    public String toString() {
        return "MailProperties(host=" + host + ", port=" + port + ", username=" + username + ", password=***, from=" + from + ")";
    }
}

package com.vaultri.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Vaultri Core API")
                .description("Production Spring Boot 3 Core Backend Service for Vaultri Seller Protection Platform")
                .version("1.0.0")
                .contact(new Contact()
                    .name("Vaultri Engineering")
                    .email("engineering@vaultri.com"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://vaultri.com")));
    }
}

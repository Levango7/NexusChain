package org.nexus.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexusGatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NexusChain Gateway API")
                        .description("NexusChain merchant payment gateway - order, payment, subscription, and webhook APIs")
                        .version("1.2.0")
                        .contact(new Contact().name("NexusChain Team")))
                .addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth"))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-NexusChain-ApiKey")
                                .description("Merchant API key for authentication")));
    }
}
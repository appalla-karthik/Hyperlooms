package dev.hyperlooms.controlplane.shared.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI hyperloomsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("HYPER_LOOMS Control Plane API")
                .version("v1")
                .description("Public HTTP contract for the HYPER_LOOMS control plane. "
                        + "Phase 0 exposes foundation endpoints only.")
                .contact(new Contact().name("HYPER_LOOMS")));
    }
}

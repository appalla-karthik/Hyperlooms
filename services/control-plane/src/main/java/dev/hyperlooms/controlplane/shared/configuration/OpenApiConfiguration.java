package dev.hyperlooms.controlplane.shared.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI hyperloomsOpenApi() {
        return new OpenAPI().components(new Components().addSecuritySchemes("requesterBearer",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .bearerFormat("JWT"))).info(new Info()
                .title("Hyperlooms Control Plane API")
                .version("v1")
                .description("Foundation endpoints and an opt-in OIDC/JWT requester intake API. "
                        + "Queueing a job does not start remote GPU execution.")
                .contact(new Contact().name("Hyperlooms")));
    }
}

package dev.hyperlooms.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import dev.hyperlooms.controlplane.provider.ProviderOperatorCommands;

import java.io.IOException;
import java.util.Arrays;

@SpringBootApplication
public class HyperloomsControlPlaneApplication {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "operator".equals(args[0])) {
            // No HTTP or gRPC listener is started in operator mode.
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    HyperloomsControlPlaneApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--hyperlooms.agent-grpc.enabled=false",
                            "--hyperlooms.lease-expiry.enabled=false",
                            "--spring.main.banner-mode=off")) {
                String result = context.getBean(ProviderOperatorCommands.class)
                        .execute(Arrays.copyOfRange(args, 1, args.length), System.in);
                System.out.println(result);
            }
        } else {
            SpringApplication.run(HyperloomsControlPlaneApplication.class, args);
        }
    }
}

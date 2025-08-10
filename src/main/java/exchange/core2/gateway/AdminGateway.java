package exchange.core2.gateway;

import exchange.core2.core.ExchangeApi;
import exchange.core2.service.AppConfig;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class AdminGateway {

    private final Server server;

    public AdminGateway(final AppConfig.GatewayConfig gatewayConfig, final ExchangeApi exchangeApi) {
        final AuthService authService = new AuthService(gatewayConfig);
        final AdminService adminService = new AdminService(exchangeApi, authService);
        this.server = ServerBuilder.forPort(gatewayConfig.getAdmin().getPort())
                .addService(adminService)
                .intercept(new AuthInterceptor(authService))
                .build();
    }

    public void start() throws IOException {
        server.start();
        log.info("AdminGateway started, listening on port {}", server.getPort());
    }

    public void stop() {
        log.info("Stopping AdminGateway...");
        try {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("AdminGateway shutdown interrupted", e);
            Thread.currentThread().interrupt();
        }
        log.info("AdminGateway stopped.");
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
}

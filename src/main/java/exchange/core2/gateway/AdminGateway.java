package exchange.core2.gateway;

import exchange.core2.core.ExchangeApi;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class AdminGateway {

    private final Server server;

    public AdminGateway(final int port, final ExchangeApi exchangeApi) {
        final AdminService adminService = new AdminService(exchangeApi);
        this.server = ServerBuilder.forPort(port)
                .addService(adminService)
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

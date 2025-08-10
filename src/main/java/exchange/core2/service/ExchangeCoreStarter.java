package exchange.core2.service;

import exchange.core2.core.ExchangeCore;
import exchange.core2.gateway.AdminGateway;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Scanner;

@Slf4j
public class ExchangeCoreStarter {

    public static void main(String[] args) {

        // This will load the configuration from application.yml
        AppConfig.getInstance();

        final ExchangeCore exchangeCore;

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Please select a startup mode:");
            System.out.println("1. Cold Start - Start from a clean state");
            System.out.println("2. Hot Start - Recover from a snapshot and journal");
            System.out.print("Enter your choice (1 or 2): ");

            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1":
                    log.info("Cold start selected, starting fresh...");
                    exchangeCore = ExchangeService.coldStart();
                    break;
                case "2":
                    log.info("Hot start selected, snapshot information is required...");
                    try {
                        System.out.print("Please enter snapshotId: ");
                        long snapshotId = Long.parseLong(scanner.nextLine().trim());
                        System.out.print("Please enter baseSeq: ");
                        long baseSeq = Long.parseLong(scanner.nextLine().trim());
                        exchangeCore = ExchangeService.hotStart(snapshotId, baseSeq);
                    } catch (NumberFormatException e) {
                        log.error("Invalid format for snapshotId or baseSeq, please enter valid numbers", e);
                        System.out.println("Invalid input format, exiting.");
                        return;
                    }
                    break;
                default:
                    log.error("Invalid choice: {}", choice);
                    System.out.println("Invalid choice, please enter 1 or 2. Exiting.");
                    return;
            }
        }

        log.info("Starting Exchange Core...");
        exchangeCore.startup();
        log.info("Exchange Core started successfully.");

        final AdminGateway adminGateway;
        try {
            final AppConfig appConfig = AppConfig.getInstance();
            adminGateway = new AdminGateway(appConfig.getGatewayConfig(), exchangeCore.getApi());
            adminGateway.start();
        } catch (IOException e) {
            log.error("Failed to start AdminGateway", e);
            // Optionally, shutdown the exchange core if the gateway is critical
            ExchangeService.shutdown();
            return;
        }


        // Add a Shutdown Hook to gracefully stop the service
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping services gracefully...");
            adminGateway.stop();
            ExchangeService.shutdown();
        }));

        try {
            adminGateway.blockUntilShutdown();
        } catch (InterruptedException e) {
            log.warn("Main thread interrupted.", e);
            Thread.currentThread().interrupt();
        }
    }
}

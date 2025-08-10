package exchange.core2.service;

import exchange.core2.core.ExchangeCore;
import lombok.extern.slf4j.Slf4j;

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

        // Add a Shutdown Hook to gracefully stop the service
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping Exchange Core gracefully...");
            ExchangeService.shutdown();
        }));

        // The main thread can finish, but the JVM will not exit because of the non-daemon threads from ExchangeCore.
        log.info("Main thread is finishing. The service will keep running in the background.");
        log.info("Press Ctrl+C to stop the service.");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.warn("Main thread interrupted.");
            Thread.currentThread().interrupt();
        }
    }
}

package exchange.core2.service;

import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;

@Slf4j
public class ExchangeService {

    public static void main(String[] args) {

        final String exchangeName = "MCE";

        // 1. Create configuration based on user interaction
        final ExchangeConfiguration config = createConfiguration(exchangeName);

        // 2. Build ExchangeCore instance
        final ExchangeCore exchangeCore = ExchangeCore.builder()
                .resultsConsumer((cmd, seq) -> {
                    // Log results here if needed
                    // log.debug("Result: {}", cmd);
                })
                .exchangeConfiguration(config)
                .build();

        // 3. Startup (non-blocking)
        log.info("Starting Exchange Core...");
        exchangeCore.startup();
        log.info("Exchange Core started successfully.");

        // 4. Add a Shutdown Hook to gracefully stop the service
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping Exchange Core gracefully...");
            exchangeCore.shutdown();
            log.info("Exchange Core has been stopped.");
        }));

        // 5. The main thread can finish, but the JVM will not exit because of the non-daemon threads from ExchangeCore.
        log.info("Main thread is finishing. The service will keep running in the background.");
        log.info("Press Ctrl+C to stop the service.");

        // To prevent the main thread from exiting immediately (which can be confusing in a simple example),
        // we can make it join itself, effectively waiting forever.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.warn("Main thread interrupted.");
            Thread.currentThread().interrupt(); // Preserve the interrupted status
        }
    }

    private static ExchangeConfiguration createConfiguration(String exchangeName) {
        log.info("Welcome to the {} Exchange Core Engine.", exchangeName);

        Scanner scanner = new Scanner(System.in);
        final InitialStateConfiguration initStateCfg;

        System.out.println("Please select a startup mode:");
        System.out.println("1. Cold Start - Start from a clean state");
        System.out.println("2. Hot Start - Recover from a snapshot and journal");
        System.out.print("Enter your choice (1 or 2): ");

        String choice = scanner.nextLine().trim();

        switch (choice) {
            case "1":
                // Cold Start
                log.info("Cold start selected, starting fresh...");
                initStateCfg = InitialStateConfiguration.cleanStartJournaling(exchangeName);
                break;

            case "2":
                // Hot Start
                log.info("Hot start selected, snapshot information is required...");

                long snapshotId = 0;
                long baseSeq = 0;

                try {
                    System.out.print("Please enter snapshotId: ");
                    snapshotId = Long.parseLong(scanner.nextLine().trim());

                    System.out.print("Please enter baseSeq: ");
                    baseSeq = Long.parseLong(scanner.nextLine().trim());

                    log.info("Performing hot start with snapshotId={}, baseSeq={}", snapshotId, baseSeq);
                    initStateCfg = InitialStateConfiguration.lastKnownStateFromJournal(exchangeName, snapshotId, baseSeq);

                } catch (NumberFormatException e) {
                    log.error("Invalid format for snapshotId or baseSeq, please enter valid numbers", e);
                    System.out.println("Invalid input format, exiting.");
                    System.exit(1);
                    throw new AssertionError(); // Should not be reached
                }
                break;

            default:
                log.error("Invalid choice: {}", choice);
                System.out.println("Invalid choice, please enter 1 or 2. Exiting.");
                System.exit(1);
                throw new AssertionError(); // Should not be reached
        }

        // Build the final configuration with production-ready settings
        return ExchangeConfiguration.defaultBuilder()
                .performanceCfg(PerformanceConfiguration.baseBuilder().build())
                .initStateCfg(initStateCfg) // Use the dynamically created config
                .serializationCfg(SerializationConfiguration.DISK_JOURNALING)
                .build();
    }
}

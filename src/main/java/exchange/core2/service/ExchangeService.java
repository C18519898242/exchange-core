package exchange.core2.service;

import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExchangeService {

    public static void main(String[] args) {

        final String exchangeName = "MCE";

        // 1. Create configuration based on command-line arguments
        final ExchangeConfiguration config = createConfiguration(exchangeName, args);

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

    private static ExchangeConfiguration createConfiguration(String exchangeName, String[] args) {
        log.info("Welcome to {} Exchange Core.", exchangeName);

        final InitialStateConfiguration initStateCfg;

        if (args.length == 0) {
            // Cold Start: No arguments provided
            log.info("No arguments provided. Performing a Cold Start.");
            initStateCfg = InitialStateConfiguration.cleanStartJournaling(exchangeName);
        } else if (args.length == 2) {
            // Hot Start: snapshotId and baseSeq are provided
            log.info("Two arguments provided. Performing a Hot Start.");
            try {
                long snapshotId = Long.parseLong(args[0]);
                long baseSeq = Long.parseLong(args[1]);
                log.info("Using snapshotId={} and baseSeq={}", snapshotId, baseSeq);
                initStateCfg = InitialStateConfiguration.lastKnownStateFromJournal(exchangeName, snapshotId, baseSeq);
            } catch (NumberFormatException e) {
                log.error("Invalid number format for snapshotId or baseSeq.", e);
                printUsageAndExit();
                throw new AssertionError(); // Should not be reached
            }
        } else {
            // Invalid number of arguments
            log.error("Invalid number of arguments.");
            printUsageAndExit();
            throw new AssertionError(); // Should not be reached
        }

        // Build the final configuration with production-ready settings
        return ExchangeConfiguration.defaultBuilder()
                .performanceCfg(PerformanceConfiguration.baseBuilder().build())
                .initStateCfg(initStateCfg) // Use the dynamically created config
                .serializationCfg(SerializationConfiguration.DISK_JOURNALING)
                .build();
    }

    private static void printUsageAndExit() {
        log.info("Usage:");
        log.info("  java -jar mce-exchange-core.jar                (for a Cold Start)");
        log.info("  java -jar mce-exchange-core.jar <snapshotId> <baseSeq> (for a Hot Start)");
        System.exit(1);
    }
}

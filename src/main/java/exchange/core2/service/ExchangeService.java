package exchange.core2.service;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public final class ExchangeService {

    public static final String EXCHANGE_NAME = "MCE";

    @Getter
    private static ExchangeCore exchangeCore;

    @Getter
    private static ExchangeApi api;

    private ExchangeService() {
        // Do not allow to create instances
    }

    private static synchronized ExchangeCore createNew(final InitialStateConfiguration initStateCfg) {
        if (exchangeCore != null) {
            throw new IllegalStateException("ExchangeCore is already initialized");
        }

        log.info("Creating a new ExchangeCore instance...");

        final ExchangeConfiguration config = ExchangeConfiguration.defaultBuilder()
                .performanceCfg(PerformanceConfiguration.baseBuilder().build())
                .initStateCfg(initStateCfg)
                .serializationCfg(SerializationConfiguration.DISK_JOURNALING)
                .build();

        exchangeCore = ExchangeCore.builder()
                .resultsConsumer((cmd, seq) -> {
                    // TODO log results if needed
                    // log.debug("Result: {}", cmd);
                })
                .exchangeConfiguration(config)
                .build();

        api = exchangeCore.getApi();

        log.info("ExchangeCore instance has been created.");
        return exchangeCore;
    }

    public static ExchangeCore coldStart() {
        return createNew(InitialStateConfiguration.cleanStartJournaling(EXCHANGE_NAME));
    }

    public static ExchangeCore hotStart(final long snapshotId, final long baseSeq) {
        return createNew(InitialStateConfiguration.lastKnownStateFromJournal(EXCHANGE_NAME, snapshotId, baseSeq));
    }


    public static void shutdown() {
        if (exchangeCore != null) {
            log.info("Shutting down ExchangeCore...");
            exchangeCore.shutdown(3000, TimeUnit.MILLISECONDS);
            log.info("ExchangeCore has been stopped.");
            exchangeCore = null;
            api = null;
        }
    }
}

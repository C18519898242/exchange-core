package exchange.core2.service;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.gateway.EventPublishService;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public final class ExchangeService {

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

        final AppConfig appConfig = AppConfig.getInstance();
        final String exchangeName = appConfig.getExchangeName();
        final String performanceProfile = appConfig.getPerformanceProfile();

        log.info("Creating a new ExchangeCore instance for {} with performance profile '{}'", exchangeName, performanceProfile);

        final PerformanceConfiguration performanceCfg = switch (performanceProfile) {
            case "latency" -> PerformanceConfiguration.latencyPerformanceBuilder().build();
            case "throughput" -> PerformanceConfiguration.throughputPerformanceBuilder().build();
            default -> PerformanceConfiguration.baseBuilder().build();
        };

        final ExchangeConfiguration config = ExchangeConfiguration.defaultBuilder()
                .performanceCfg(performanceCfg)
                .initStateCfg(initStateCfg)
                .serializationCfg(SerializationConfiguration.DISK_JOURNALING)
                .build();

        final IEventsHandler eventsHandler = new EventPublishService();

        exchangeCore = ExchangeCore.builder()
                .resultsConsumer((cmd, seq) -> {
                    if (cmd.command == OrderCommandType.ADD_USER) {
                        ApiAddUser apiAddUser = new ApiAddUser(cmd.uid);
                        eventsHandler.commandResult(new IEventsHandler.ApiCommandResult(apiAddUser, cmd.resultCode, seq));
                    }
                })
                .eventsHandler(eventsHandler)
                .exchangeConfiguration(config)
                .build();

        api = exchangeCore.getApi();

        log.info("ExchangeCore instance has been created.");
        return exchangeCore;
    }

    public static ExchangeCore coldStart() {
        return createNew(InitialStateConfiguration.cleanStartJournaling(AppConfig.getInstance().getExchangeName()));
    }

    public static ExchangeCore hotStart(final long snapshotId, final long baseSeq) {
        return createNew(InitialStateConfiguration.lastKnownStateFromJournal(AppConfig.getInstance().getExchangeName(), snapshotId, baseSeq));
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

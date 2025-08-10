package exchange.core2.service;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Objects;

@Slf4j
public final class AppConfig {

    private static final AppConfig INSTANCE = new AppConfig();

    @Getter
    private final String performanceProfile;

    @Getter
    private final String exchangeName;

    private AppConfig() {
        final String configPath = "application.yml";
        final Yaml yaml = new Yaml();
        final InputStream inputStream = this.getClass()
                .getClassLoader()
                .getResourceAsStream(configPath);

        if (inputStream == null) {
            log.error("Configuration file not found at: {}", configPath);
            throw new IllegalStateException("Configuration file not found: " + configPath);
        }

        final RootConfig rootConfig = yaml.loadAs(inputStream, RootConfig.class);
        final CoreConfig coreConfig = Objects.requireNonNull(rootConfig.getCore(), "core section is not defined in " + configPath);
        final PerformanceConfig performanceConfig = Objects.requireNonNull(coreConfig.getPerformance(), "performance section is not defined in " + configPath);

        this.performanceProfile = Objects.requireNonNull(performanceConfig.getProfile(), "performance.profile is not defined in " + configPath);
        this.exchangeName = Objects.requireNonNull(coreConfig.getExchangeName(), "exchange-name is not defined in " + configPath);

        log.info("Configuration loaded: profile={}, name={}", performanceProfile, exchangeName);
    }

    public static AppConfig getInstance() {
        return INSTANCE;
    }

    // --- YAML Deserialization POJOs ---

    @Setter
    @Getter
    public static class RootConfig {
        private CoreConfig core;
    }

    @Setter
    @Getter
    public static class CoreConfig {
        private PerformanceConfig performance;
        private String exchangeName;
    }

    @Setter
    @Getter
    public static class PerformanceConfig {
        private String profile;
    }
}

package exchange.core2.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
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

        final Map<String, Object> config = yaml.load(inputStream);
        final Map<String, Object> coreConfig = (Map<String, Object>) config.get("core");
        final Map<String, String> performanceConfig = (Map<String, String>) coreConfig.get("performance");

        this.performanceProfile = Objects.requireNonNull(performanceConfig.get("profile"), "performance.profile is not defined in " + configPath);
        this.exchangeName = Objects.requireNonNull((String) coreConfig.get("exchange-name"), "exchange-name is not defined in " + configPath);

        log.info("Configuration loaded: profile={}, name={}", performanceProfile, exchangeName);
    }

    public static AppConfig getInstance() {
        return INSTANCE;
    }
}

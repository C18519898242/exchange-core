package exchange.core2.gateway;

import exchange.core2.service.AppConfig;
import io.grpc.Context;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class AuthService {

    public static final Context.Key<String> USERNAME_CONTEXT_KEY = Context.key("username");
    public static final io.grpc.Metadata.Key<String> AUTH_TOKEN_METADATA_KEY = io.grpc.Metadata.Key.of("auth-token", io.grpc.Metadata.ASCII_STRING_MARSHALLER);


    private final Map<String, AppConfig.UserConfig> users;
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>(); // token -> username
    private final Map<String, String> userToToken = new ConcurrentHashMap<>(); // username -> token

    public AuthService(AppConfig.GatewayConfig gatewayConfig) {
        this.users = gatewayConfig.getAdmin().getUsers().stream()
                .collect(Collectors.toMap(AppConfig.UserConfig::getUsername, Function.identity()));
    }

    public String login(String username, String password) {
        AppConfig.UserConfig user = users.get(username);
        if (user == null || !PasswordUtils.verifyPassword(password, user.getPassword())) {
            return null;
        }

        // Handle single-session logic
        if (userToToken.containsKey(username)) {
            String oldToken = userToToken.remove(username);
            activeSessions.remove(oldToken);
            log.warn("User {} logged in from a new location, terminating the old session.", username);
        }

        String token = UUID.randomUUID().toString();
        activeSessions.put(token, username);
        userToToken.put(username, token);
        log.info("User {} logged in successfully with token.", username);
        return token;
    }

    public String getUsernameForToken(String token) {
        return activeSessions.get(token);
    }
}

package exchange.core2.gateway;

import exchange.core2.service.AppConfig;
import io.grpc.Context;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class AuthService {

    public static final Context.Key<String> USERNAME_CONTEXT_KEY = Context.key("username");

    private final Map<String, AppConfig.UserConfig> users;
    private final Map<String, Context.CancellableContext> activeSessions = new ConcurrentHashMap<>();

    public AuthService(AppConfig.GatewayConfig gatewayConfig) {
        this.users = gatewayConfig.getAdmin().getUsers().stream()
                .collect(Collectors.toMap(AppConfig.UserConfig::getUsername, Function.identity()));
    }

    public boolean login(String username, String password, Context.CancellableContext context) {
        AppConfig.UserConfig user = users.get(username);
        if (user == null || !PasswordUtils.verifyPassword(password, user.getPassword())) {
            return false;
        }

        // Handle single-session logic
        Context.CancellableContext oldContext = activeSessions.put(username, context);
        if (oldContext != null) {
            log.warn("User {} logged in from a new location, terminating the old session.", username);
            oldContext.cancel(Status.CANCELLED.withDescription("Logged in from another location").asRuntimeException());
        }

        context.addListener(c -> {
            activeSessions.remove(username);
            log.info("User {} session terminated.", username);
        }, Runnable::run);

        return true;
    }
}

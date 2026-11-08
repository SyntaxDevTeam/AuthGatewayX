package pl.syntaxdevteam.authgatewayx.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Compile-time contract fixture: consumers need no domain value classes or platform implementation. */
public final class JavaPluginConsumer implements AutoCloseable {
    private final AuthGatewayApi api;
    private final AuthSubscription subscription;

    public JavaPluginConsumer(AuthGatewayApi api, Consumer<AuthEvent> listener) {
        this.api = api;
        this.subscription = api.events().subscribe(listener);
    }

    public UUID accountId(UUID playerId) {
        AccountSnapshot account = api.getAccount(playerId);
        return account == null ? null : account.getAccountId();
    }

    public CompletionStage<AccountSnapshot> find(String username) {
        return api.findAccountByUsername(username);
    }

    public CompletionStage<Boolean> revoke(UUID playerId) {
        return api.invalidateSession(playerId);
    }

    public pl.syntaxdevteam.authgatewayx.integrations.UsernamePolicyProvider usernamePolicy() {
        return username -> java.util.concurrent.CompletableFuture.completedFuture(
            pl.syntaxdevteam.authgatewayx.integrations.UsernameVerdict.ALLOW);
    }

    @Override public void close() { subscription.close(); }
}

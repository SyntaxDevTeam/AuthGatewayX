package pl.syntaxdevteam.logincorex.velocity.loader;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryManager;
import net.byteflux.libby.VelocityLibraryManager;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Java-only entry point: no Kotlin classes are resolved before the runtime classpath is ready. */
public final class SyntaxLoader {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private Object runtime;
    private volatile boolean initialized;

    @Subscribe
    public void preLogin(com.velocitypowered.api.event.connection.PreLoginEvent event) {
        if (!initialized) event.setResult(com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult.denied(
            net.kyori.adventure.text.Component.text("LoginCoreX is not ready")));
    }

    @Inject
    public SyntaxLoader(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) throws Exception {
        // The plugin instance is registered by this point, so addToClasspath can identify its owner.
        LibraryManager libraries = new VelocityLibraryManager<>(logger, directory, proxy.getPluginManager(), this);
        libraries.addRepository("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/");
        libraries.addRepository("https://nexus.syntaxdevteam.pl/repository/maven-releases/");
        libraries.addMavenCentral();
        var input = getClass().getClassLoader().getResourceAsStream("velocity-libraries.txt");
        if (input == null) throw new IllegalStateException("Missing runtime manifest");
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] parts = line.split(":", 4);
                if (parts.length != 4) throw new IllegalStateException("Invalid runtime coordinate");
                libraries.loadLibrary(Library.builder().groupId(parts[0]).artifactId(parts[1])
                    .version(parts[2]).checksum(parts[3]).build());
            }
        }
        Class<?> type = Class.forName("pl.syntaxdevteam.logincorex.velocity.LoginCoreXVelocity", true, getClass().getClassLoader());
        runtime = type.getConstructor(ProxyServer.class, Object.class, Logger.class, Path.class)
            .newInstance(proxy, this, logger, directory);
        type.getMethod("onInitialize", ProxyInitializeEvent.class).invoke(runtime, event);
        initialized = true;
    }

    /** Returns the platform-independent ProxyGatewayApi after runtime initialization, otherwise null.
     * Object keeps the bootstrap entry point independent of Kotlin before runtime libraries load.
     */
    public Object getApi() throws ReflectiveOperationException {
        Object current = runtime;
        return initialized && current != null ? current.getClass().getMethod("getApi").invoke(current) : null;
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) throws Exception {
        initialized = false;
        if (runtime != null) runtime.getClass().getMethod("onShutdown", ProxyShutdownEvent.class).invoke(runtime, event);
    }
}

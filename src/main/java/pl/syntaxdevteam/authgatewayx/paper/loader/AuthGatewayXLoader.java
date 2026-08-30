package pl.syntaxdevteam.authgatewayx.paper.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import org.jetbrains.annotations.NotNull;

/**
 * Paper classpath composition point. Runtime libraries will be added here once
 * their release coordinates are fixed in paper-libraries.yml.
 */
public final class AuthGatewayXLoader implements PluginLoader {
    @Override
    public void classloader(final @NotNull PluginClasspathBuilder builder) {
        // Deliberately empty: no unversioned or SNAPSHOT runtime dependency is loaded.
    }
}

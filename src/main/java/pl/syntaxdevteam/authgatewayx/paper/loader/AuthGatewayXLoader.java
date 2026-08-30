package pl.syntaxdevteam.authgatewayx.paper.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Paper classpath composition point. The plugin JAR contains project code only;
 * external runtime libraries are resolved before the plugin classloader starts.
 */
public final class AuthGatewayXLoader implements PluginLoader {
    @Override
    public void classloader(final @NotNull PluginClasspathBuilder builder) {
        final MavenLibraryResolver resolver = new MavenLibraryResolver();
        try (InputStream stream = Objects.requireNonNull(
                AuthGatewayXLoader.class.getClassLoader().getResourceAsStream("paper-libraries.yml"),
                "paper-libraries.yml is missing")) {
            new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("- "))
                    .map(line -> line.substring(2).trim())
                    .map(DefaultArtifact::new)
                    .forEach(artifact -> resolver.addDependency(new Dependency(artifact, null)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read AuthGatewayX runtime libraries", failure);
        }
        resolver.addRepository(new RemoteRepository.Builder("syntax-snapshots", "default", "https://nexus.syntaxdevteam.pl/repository/maven-snapshots/").build());
        resolver.addRepository(new RemoteRepository.Builder("syntax-releases", "default", "https://nexus.syntaxdevteam.pl/repository/maven-releases/").build());
        resolver.addRepository(new RemoteRepository.Builder("paper-central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        builder.addLibrary(resolver);
    }
}

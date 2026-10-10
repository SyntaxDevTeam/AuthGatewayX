package pl.syntaxdevteam.authgatewayx.paper.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves the catalog-generated runtime manifest before plugin bootstrap. */
@SuppressWarnings("UnstableApiUsage")
public final class SyntaxLoader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder builder) {
        try (InputStream input = SyntaxLoader.class.getClassLoader().getResourceAsStream("paper-libraries.yml")) {
            if (input == null) throw new IllegalStateException("paper-libraries.yml is missing");
            Object document = new Yaml().load(input);
            if (!(document instanceof Map<?, ?> config)
                || !(config.get("repositories") instanceof Map<?, ?> repositories)
                || !(config.get("libraries") instanceof java.util.List<?> libraries)
                || libraries.isEmpty()) {
                throw new IllegalStateException("Invalid or empty runtime manifest");
            }
            Map<String, MavenLibraryResolver> resolvers = new LinkedHashMap<>();
            for (Object value : libraries) {
                if (!(value instanceof String coordinate) || coordinate.contains("${")) {
                    throw new IllegalStateException("Invalid library coordinate: " + value);
                }
                String repositoryId = coordinate.startsWith("pl.syntaxdevteam:")
                    ? (coordinate.endsWith("-SNAPSHOT") ? "syntaxdevteam-snapshots" : "syntaxdevteam-releases")
                    : "central";
                MavenLibraryResolver resolver = resolvers.computeIfAbsent(repositoryId, id -> {
                    Object url = repositories.get(id);
                    if (!(url instanceof String address) || !address.startsWith("https://")) {
                        throw new IllegalStateException("Missing HTTPS repository: " + id);
                    }
                    MavenLibraryResolver created = new MavenLibraryResolver();
                    created.addRepository(new RemoteRepository.Builder(id, "default", address).build());
                    // SyntaxDevTeam artifacts have transitive dependencies in Maven Central.
                    if (!id.equals("central")) {
                        created.addRepository(new RemoteRepository.Builder("central", "default",
                            MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
                    }
                    return created;
                });
                resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
            }
            resolvers.values().forEach(builder::addLibrary);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot resolve AuthGatewayX runtime libraries", failure);
        }
    }
}

package org.homio.app.extloader;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.homio.api.AddonConfiguration;
import org.homio.api.AddonEntrypoint;
import org.homio.api.exception.ServerException;
import org.homio.api.util.CommonUtils;
import org.homio.api.util.SpringUtils;
import org.homio.app.HomioClassLoader;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import sun.reflect.ReflectionFactory;

@Getter
@Setter
public class AddonContext {

    private static MavenXpp3Reader pomReader = new MavenXpp3Reader();

    private final Path contextFile; // batch context file associated with this context
    private final Model pomFile;
    private final Manifest manifest;
    private String addonID;

    private boolean internal;
    private boolean installed;
    private AddonSpringContext config;
    private String loadError;

    @SneakyThrows
    AddonContext(Path contextFile) {
        this.contextFile = contextFile;
        ZipFile zipFile = new ZipFile(contextFile.toString());
        JarInputStream jarStream = new JarInputStream(new FileInputStream(contextFile.toString()));
        this.manifest = jarStream.getManifest();
        this.pomFile = readPomFile(zipFile);
        jarStream.close();
        zipFile.close();
    }

    AddonContext(String addonID) {
        this.addonID = addonID;
        this.contextFile = null;
        this.pomFile = null;
        this.manifest = null;
        this.internal = true;
    }

    public boolean isLoaded() {
        return config != null && loadError == null;
    }

    public Set<String> getDependencies() {
        return this.pomFile.getDependencies().stream()
                           .filter(d -> d.getGroupId().equals("org.homio") && d.getArtifactId().contains("addon"))
                           .map(Dependency::getArtifactId).collect(Collectors.toSet());
    }

    public String getAddonFriendlyName() {
        return StringUtils.defaultString(this.pomFile.getName(), addonID);
    }

    public ApplicationContext getApplicationContext() {
        return config.ctx;
    }

    public String getBasePackage() {
        return this.config.configClass.getPackage().getName();
    }

    public String getVersion() {
        String version = this.pomFile.getVersion();
        if (version == null && this.pomFile.getParent() != null) {
            version = this.pomFile.getParent().getVersion();
        }
        if (version == null) {
            throw new ServerException("Unable to find version for addon: " + addonID);
        }
        return version;
    }

    @SneakyThrows
    void load(ConfigurationBuilder configurationBuilder, Environment env, ApplicationContext parentContext,
        ClassLoader classLoader) {
        URL addonUrl = contextFile.toUri().toURL();
        Reflections reflections = new Reflections(configurationBuilder.setUrls(addonUrl));

        config = new AddonSpringContext(env);
        addonID = config.fetchAddonID(reflections, pomFile.getArtifactId());
        try {
            HomioClassLoader.addClassLoaders(addonID, classLoader);
            config.configureSpringContext(reflections, parentContext, addonID, classLoader);
        } catch (Exception ex) {
            HomioClassLoader.removeClassLoader(addonID);
            loadError = CommonUtils.getErrorMessage(ex);
            throw ex;
        }
    }

    private Model readPomFile(ZipFile file) throws IOException, XmlPullParserException {
        String artifactId = this.manifest.getMainAttributes().getValue("artifactId");
        for (ZipEntry e : Collections.list(file.entries())) {
            if (e.getName().endsWith(artifactId + "/pom.xml")) {
                return pomReader.read(file.getInputStream(e));
            }
        }
        throw new ServerException("Unable to find pom.xml in jar");
    }

    @RequiredArgsConstructor
    public static class AddonSpringContext {

        private final Environment env;
        private AnnotationConfigApplicationContext ctx;
        private Class<?> configClass;

        public String fetchAddonID(Reflections reflections, String artifactId) {
            Set<Class<? extends AddonEntrypoint>> addonEntrypointSet = reflections.getSubTypesOf(AddonEntrypoint.class);
            if (addonEntrypointSet.isEmpty()) {
                throw new ServerException("Found no AddonEntrypoint in context of addon: " + artifactId);
            }
            if (addonEntrypointSet.size() > 1) {
                throw new ServerException("Found multiple AddonEntrypoint in context of addon: " + artifactId);
            }
            Class<? extends AddonEntrypoint> entrypointClass = addonEntrypointSet.iterator().next();

            AddonEntrypoint addonEntrypoint = createClassInstance(entrypointClass);
            return addonEntrypoint.getAddonID();
        }

        void configureSpringContext(Reflections reflections, ApplicationContext parentContext, String addonID, ClassLoader classLoader) {
            configClass = findBatchConfigurationClass(reflections);
            AddonConfiguration addonConfiguration = configClass.getDeclaredAnnotation(AddonConfiguration.class);

            // create spring context
            ctx = new AnnotationConfigApplicationContext();
            ctx.setId(addonID);
            ctx.setParent(parentContext);
            ctx.setClassLoader(classLoader);
            ctx.setResourceLoader(new PathMatchingResourcePatternResolver(classLoader));

            // set custom environments
            Map<String, Object> customEnv = Stream.of(addonConfiguration.env()).collect(
                Collectors.toMap(AddonConfiguration.Env::key, e ->
                    SpringUtils.replaceEnvValues(e.value(),
                        (key, defValue, fullPrefix) -> env.getProperty(key, defValue))));

            if (!customEnv.isEmpty()) {
                ctx.getEnvironment().getPropertySources()
                   .addFirst(new MapPropertySource("AddonConfiguration PropertySource", customEnv));
            }

            // wake up spring context
            ctx.scan(configClass.getPackage().getName());
            ctx.register(configClass);

            ctx.refresh();
            ctx.start();
        }

        void destroy() {
            ctx.close();
        }

        @SneakyThrows
        private static <T> T createClassInstance(Class<T> clazz) {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<?> objDef = ((Class<? super T>) Object.class).getDeclaredConstructor();
            Constructor<?> intConstr = rf.newConstructorForSerialization(clazz, objDef);
            return clazz.cast(intConstr.newInstance());
        }

        /**
         * Find spring configuration class with annotation @AddonConfiguration and @Configuration
         */
        private Class<?> findBatchConfigurationClass(Reflections reflections) {
            // find configuration class
            Set<Class<?>> springConfigClasses = reflections.getTypesAnnotatedWith(AddonConfiguration.class);
            if (springConfigClasses.isEmpty()) {
                throw new ServerException(
                    "Configuration class with annotation @AddonConfiguration not found. Not possible to create spring " +
                        "context");
            }
            if (springConfigClasses.size() > 1) {
                throw new ServerException("Configuration class with annotation @AddonConfiguration must be unique, but found: " +
                    StringUtils.join(springConfigClasses, ", "));
            }
            Class<?> batchConfigurationClass = springConfigClasses.iterator().next();
            if (batchConfigurationClass.getDeclaredAnnotation(AddonConfiguration.class) == null) {
                throw new ServerException("Loaded batch definition has different ws-service-api.jar version and can not be instantiated");
            }
            return batchConfigurationClass;
        }
    }
}

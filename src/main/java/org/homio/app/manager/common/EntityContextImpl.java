package org.homio.app.manager.common;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.homio.bundle.api.util.CommonUtils.MACHINE_IP_ADDRESS;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.text.DateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.persistence.EntityManagerFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.hibernate.metamodel.spi.MetamodelImplementor;
import org.hibernate.persister.entity.Joinable;
import org.homio.app.LogService;
import org.homio.app.audio.AudioService;
import org.homio.app.auth.JwtTokenProvider;
import org.homio.app.builder.widget.EntityContextWidgetImpl;
import org.homio.app.cb.ComputerBoardEntity;
import org.homio.app.config.AppProperties;
import org.homio.app.config.ExtRequestMappingHandlerMapping;
import org.homio.app.extloader.BundleContext;
import org.homio.app.extloader.BundleContextService;
import org.homio.app.manager.BundleService;
import org.homio.app.manager.CacheService;
import org.homio.app.manager.LoggerService;
import org.homio.app.manager.PortService;
import org.homio.app.manager.ScriptService;
import org.homio.app.manager.UserService;
import org.homio.app.manager.WidgetService;
import org.homio.app.manager.common.impl.EntityContextBGPImpl;
import org.homio.app.manager.common.impl.EntityContextEventImpl;
import org.homio.app.manager.common.impl.EntityContextInstallImpl;
import org.homio.app.manager.common.impl.EntityContextSettingImpl;
import org.homio.app.manager.common.impl.EntityContextUIImpl;
import org.homio.app.manager.common.impl.EntityContextVarImpl;
import org.homio.app.model.entity.widget.WidgetBaseEntity;
import org.homio.app.repository.SettingRepository;
import org.homio.app.repository.VariableDataRepository;
import org.homio.app.repository.crud.base.BaseCrudRepository;
import org.homio.app.repository.device.AllDeviceRepository;
import org.homio.app.rest.ConsoleController;
import org.homio.app.rest.FileSystemController;
import org.homio.app.rest.ItemController;
import org.homio.app.rest.SettingController;
import org.homio.app.service.cloud.CloudService;
import org.homio.app.setting.ScanMicroControllersSetting;
import org.homio.app.setting.ScanVideoStreamSourcesSetting;
import org.homio.app.setting.system.SystemClearCacheButtonSetting;
import org.homio.app.setting.system.SystemLanguageSetting;
import org.homio.app.setting.system.SystemShowEntityStateSetting;
import org.homio.app.setting.system.auth.SystemUserSetting;
import org.homio.app.spring.ContextCreated;
import org.homio.app.spring.ContextRefreshed;
import org.homio.app.utils.HardwareUtils;
import org.homio.app.utils.InternalUtil;
import org.homio.app.workspace.BroadcastLockManagerImpl;
import org.homio.app.workspace.WorkspaceService;
import org.homio.bundle.api.BundleEntrypoint;
import org.homio.bundle.api.EntityContext;
import org.homio.bundle.api.entity.BaseEntity;
import org.homio.bundle.api.entity.DeviceBaseEntity;
import org.homio.bundle.api.entity.DisableCacheEntity;
import org.homio.bundle.api.entity.UserEntity;
import org.homio.bundle.api.entity.storage.BaseFileSystemEntity;
import org.homio.bundle.api.exception.NotFoundException;
import org.homio.bundle.api.model.HasEntityIdentifier;
import org.homio.bundle.api.model.Status;
import org.homio.bundle.api.model.UpdatableValue;
import org.homio.bundle.api.repository.AbstractRepository;
import org.homio.bundle.api.repository.GitHubProject;
import org.homio.bundle.api.repository.PureRepository;
import org.homio.bundle.api.service.scan.BeansItemsDiscovery;
import org.homio.bundle.api.service.scan.MicroControllerScanner;
import org.homio.bundle.api.service.scan.VideoStreamScanner;
import org.homio.bundle.api.setting.SettingPlugin;
import org.homio.bundle.api.util.CommonUtils;
import org.homio.bundle.api.util.Lang;
import org.homio.bundle.api.util.UpdatableSetting;
import org.homio.bundle.api.widget.WidgetBaseTemplate;
import org.homio.bundle.api.workspace.scratch.Scratch3ExtensionBlocks;
import org.homio.bundle.hquery.hardware.network.NetworkHardwareRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Log4j2
@Component
public class EntityContextImpl implements EntityContext {

    private final GitHubProject appGitHub = GitHubProject.of("homiodev", "homio-app");
    public static final String CREATE_TABLE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS %s_entity_id ON %s (entityid)";
    private static final Set<Class<? extends ContextCreated>> BEAN_CONTEXT_CREATED = new LinkedHashSet<>();
    private static final Set<Class<? extends ContextRefreshed>> BEAN_CONTEXT_REFRESH = new LinkedHashSet<>();
    // count how much addBundle/removeBundle invokes
    public static int BUNDLE_UPDATE_COUNT = 0;
    public static Map<String, AbstractRepository> repositories = new HashMap<>();
    public static Map<String, Class<? extends BaseEntity>> baseEntityNameToClass;
    public static Map<String, AbstractRepository> repositoriesByPrefix;
    private static final Map<String, PureRepository> pureRepositories = new HashMap<>();

    static {
        BEAN_CONTEXT_CREATED.add(BundleService.class);

        BEAN_CONTEXT_CREATED.add(LogService.class);
        BEAN_CONTEXT_CREATED.add(FileSystemController.class);
        BEAN_CONTEXT_CREATED.add(ItemController.class);
        BEAN_CONTEXT_CREATED.add(PortService.class);
        BEAN_CONTEXT_CREATED.add(LoggerService.class);
        BEAN_CONTEXT_CREATED.add(WidgetService.class);
        BEAN_CONTEXT_CREATED.add(BundleContextService.class);
        BEAN_CONTEXT_CREATED.add(ScriptService.class);
        BEAN_CONTEXT_CREATED.add(JwtTokenProvider.class);
        BEAN_CONTEXT_CREATED.add(CloudService.class);

        BEAN_CONTEXT_REFRESH.add(FileSystemController.class);
        BEAN_CONTEXT_REFRESH.add(BundleService.class);
        BEAN_CONTEXT_REFRESH.add(ConsoleController.class);
        BEAN_CONTEXT_REFRESH.add(SettingRepository.class);
        BEAN_CONTEXT_REFRESH.add(SettingController.class);
        BEAN_CONTEXT_REFRESH.add(WorkspaceService.class);
        BEAN_CONTEXT_REFRESH.add(ItemController.class);
        BEAN_CONTEXT_REFRESH.add(PortService.class);
        BEAN_CONTEXT_REFRESH.add(AudioService.class);
    }

    private final EntityContextUIImpl entityContextUI;
    private final EntityContextInstallImpl entityContextInstall;
    private final EntityContextEventImpl entityContextEvent;
    private final EntityContextBGPImpl entityContextBGP;
    private final EntityContextSettingImpl entityContextSetting;
    private final EntityContextVarImpl entityContextVar;
    private final EntityContextWidgetImpl entityContextWidget;
    private final Environment environment;
    @Getter private final EntityContextStorage entityContextStorage;
    private final ClassFinder classFinder;
    @Getter private final CacheService cacheService;
    @Getter private final AppProperties appProperties;
    @Getter private final Map<String, InternalBundleContext> bundles = new LinkedHashMap<>();
    private final Set<ApplicationContext> allApplicationContexts = new HashSet<>();
    private EntityManager entityManager;
    private TransactionTemplate transactionTemplate;
    private boolean showEntityState;
    private ApplicationContext applicationContext;
    private AllDeviceRepository allDeviceRepository;
    private PlatformTransactionManager transactionManager;
    private WorkspaceService workspaceService;

    public EntityContextImpl(
        ClassFinder classFinder,
        CacheService cacheService,
        ThreadPoolTaskScheduler taskScheduler,
        SimpMessagingTemplate messagingTemplate,
        Environment environment,
        EntityManagerFactory entityManagerFactory,
        VariableDataRepository variableDataRepository,
        AppProperties appProperties) {
        this.classFinder = classFinder;
        this.environment = environment;
        this.cacheService = cacheService;
        this.appProperties = appProperties;

        this.entityContextUI = new EntityContextUIImpl(this, messagingTemplate);
        this.entityContextBGP = new EntityContextBGPImpl(this, taskScheduler, appProperties);
        this.entityContextEvent = new EntityContextEventImpl(this, entityManagerFactory);
        this.entityContextInstall = new EntityContextInstallImpl(this);
        this.entityContextSetting = new EntityContextSettingImpl(this);
        this.entityContextWidget = new EntityContextWidgetImpl(this);
        this.entityContextStorage = new EntityContextStorage(this);
        this.entityContextVar = new EntityContextVarImpl(this, variableDataRepository);
    }

    @SneakyThrows
    public void afterContextStart(ApplicationContext applicationContext) {
        this.allApplicationContexts.add(applicationContext);
        this.applicationContext = applicationContext;
        MACHINE_IP_ADDRESS = getInternalIpAddress();

        this.transactionManager = this.applicationContext.getBean(PlatformTransactionManager.class);
        this.allDeviceRepository = this.applicationContext.getBean(AllDeviceRepository.class);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workspaceService = applicationContext.getBean(WorkspaceService.class);
        this.entityManager = applicationContext.getBean(EntityManager.class);

        rebuildAllRepositories(applicationContext, true);

        userConfiguration();

        ComputerBoardEntity.ensureDeviceExists(this);
        setting().fetchSettingPlugins(null, classFinder, true);

        entityContextVar.onContextCreated();
        entityContextUI.onContextCreated();
        entityContextBGP.onContextCreated();

        for (Class<? extends ContextCreated> beanUpdateClass : BEAN_CONTEXT_CREATED) {
            applicationContext.getBean(beanUpdateClass).onContextCreated(this);
        }
        updateBeans(null, applicationContext, true);

        setting().listenValueAndGet(SystemShowEntityStateSetting.class, "im-show-entity-states", value -> this.showEntityState = value);

        initialiseInlineBundles(applicationContext);

        bgp().builder("app-version").interval(Duration.ofDays(1)).delay(Duration.ofSeconds(1))
             .execute(this::updateNotificationBlock);

        event().fireEventIfNotSame("app-status", Status.ONLINE);
        event().runOnceOnInternetUp("internal-ctx", () -> {
            MACHINE_IP_ADDRESS = getInternalIpAddress();

        });
        setting().listenValue(SystemClearCacheButtonSetting.class, "im-clear-cache", () -> {
            cacheService.clearCache();
            ui().sendSuccessMessage("Cache has been cleared successfully");
        });
        setting().listenValueAndGet(SystemLanguageSetting.class, "listen-lang", lang -> Lang.CURRENT_LANG = lang.name());
        setting().listenValue(ScanMicroControllersSetting.class, "scan-micro-controllers", () -> {
            ui().handleResponse(new BeansItemsDiscovery(MicroControllerScanner.class).handleAction(this, null));
        });
        setting().listenValue(ScanVideoStreamSourcesSetting.class, "scan-video-sources", () -> {
            ui().handleResponse(new BeansItemsDiscovery(VideoStreamScanner.class).handleAction(this, null));
        });

        this.entityContextStorage.init();
    }

    private void userConfiguration() {
        getBean(UserService.class).ensureUserExists();

        setting().listenValueInRequest(SystemUserSetting.class, "user", json -> {
            if (json != null) {
                UserEntity user = getUserRequire();
                // authenticate
                AuthenticationProvider authenticationProvider = getBean(AuthenticationProvider.class);
                authenticationProvider.authenticate(new UsernamePasswordAuthenticationToken(
                    user.getUserId(), json.getString("field.currentPassword")));
                if (!json.getString("field.newPassword").equals(json.getString("field.repeatNewPassword"))) {
                    throw new IllegalArgumentException("user.password_not_match");
                }

                PasswordEncoder passwordEncoder = getBean(PasswordEncoder.class);
                save(user
                    .setUserId(json.getString("field.email"))
                    .setName(json.getString("field.name"))
                    .setPassword(json.getString("field.newPassword"), passwordEncoder));
                ui().sendSuccessMessage("user.altered");
                ui().reloadWindow("user.altered_reload");
            }
        });
    }

    @Override
    public EntityContextInstallImpl install() {
        return this.entityContextInstall;
    }

    @Override
    public EntityContextUIImpl ui() {
        return entityContextUI;
    }

    @Override
    public EntityContextEventImpl event() {
        return this.entityContextEvent;
    }

    private void updateNotificationBlock() {
        ui().addNotificationBlock("app", "App", "fas fa-house", "#E65100", builder -> {
            String installedVersion = appProperties.getVersion();
            builder.setVersion(installedVersion);
            String latestVersion = appGitHub.getLastReleaseVersion();
            if (!installedVersion.equals(latestVersion)) {
                builder.setUpdatable(
                    (progressBar, version) -> appGitHub.updating("homio", CommonUtils.getInstallPath().resolve("homio"), progressBar,
                        projectUpdate -> {
                            projectUpdate.downloadSource(version);
                            long pid = ProcessHandle.current().pid();
                            Path updateScript = CommonUtils.getInstallPath().resolve("app-update." + (IS_OS_WINDOWS ? "sh" : "bat"));
                            String jarLocation = EntityContextImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
                            String content = format(IS_OS_WINDOWS ? "@echo off\ntaskkill /F /PID %s\nmove %s %s\nstart javaw -jar \"%s\"\nexit"
                                    : "#!/bin/bash\nkill -9 %s\nmv %s %s\nnohup sudo java -jar %s &>/dev/null &", pid,
                                projectUpdate.getProjectPath(), jarLocation, jarLocation);
                            CommonUtils.writeToFile(updateScript, content, false);
                            Runtime.getRuntime().exec(updateScript.toString());
                            return null;
                        }), appGitHub.getReleasesSince(installedVersion));
            }
            builder.addInfo("Started at " + DateFormat.getDateTimeInstance().format(new Date()), null, "fas fa-clock", null);
        });
    }

    @Override
    public EntityContextBGPImpl bgp() {
        return entityContextBGP;
    }

    @Override
    public EntityContextSettingImpl setting() {
        return entityContextSetting;
    }

    @Override
    public EntityContextVarImpl var() {
        return entityContextVar;
    }

    @Override
    public void registerScratch3Extension(Scratch3ExtensionBlocks scratch3ExtensionBlocks) {
        workspaceService.registerScratch3Extension(scratch3ExtensionBlocks);
    }

    public EntityContextWidgetImpl widget() {
        return this.entityContextWidget;
    }

    @Override
    public <T extends BaseEntity> T getEntity(@NotNull String entityID, boolean useCache) {
        T baseEntity = useCache ? entityManager.getEntityWithFetchLazy(entityID) : entityManager.getEntityNoCache(entityID);
        if (baseEntity == null) {
            baseEntity = entityManager.getEntityNoCache(entityID);
            if (baseEntity != null) {
                cacheService.clearCache();
            }
        }

        if (baseEntity != null) {
            cacheService.merge(baseEntity);
            baseEntity.afterFetch(this);
        }
        return baseEntity;
    }

    @Override
    public Optional<AbstractRepository> getRepository(@NotNull String entityID) {
        return entityManager.getRepositoryByEntityID(entityID);
    }

    @Override
    public AbstractRepository getRepository(Class<? extends BaseEntity> entityClass) {
        return classFinder.getRepositoryByClass(entityClass);
    }

    @Override
    public <T extends HasEntityIdentifier> void createDelayed(T entity) {
        putToCache(entity, null);
    }

    @Override
    public <T extends HasEntityIdentifier> void updateDelayed(T entity, Consumer<T> consumer) {
        Map<String, Object[]> changeFields = new HashMap<>();
        MethodInterceptor handler = (obj, method, args, proxy) -> {
            String setName = method.getName();
            if (setName.startsWith("set")) {
                Object oldValue;
                try {
                    oldValue = cacheService.getFieldValue(entity.getIdentifier(), setName);
                    if (oldValue == null) {
                        oldValue = MethodUtils.invokeMethod(entity, method.getName().replaceFirst("set", "get"));
                    }
                } catch (NoSuchMethodException ex) {
                    oldValue = MethodUtils.invokeMethod(entity, method.getName().replaceFirst("set", "is"));
                }
                Object newValue = setName.startsWith("setJsonData") ? args[1] : args[0];
                if (!Objects.equals(oldValue, newValue)) {
                    changeFields.put(setName, args);
                }
            }
            if (method.getReturnType().isAssignableFrom(entity.getClass())) {
                proxy.invoke(entity, args);
                return obj;
            }
            return proxy.invoke(entity, args);
        };

        T proxyInstance = (T) Enhancer.create(entity.getClass(), handler);
        consumer.accept(proxyInstance);

        // fire entityUpdateListeners only if method called not from transaction
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            BaseEntity oldEntity = entityManager.getEntityNoCache(entity.getEntityID());
            runUpdateNotifyListeners(entity, oldEntity, event().getEntityUpdateListeners());
        }

        if (!changeFields.isEmpty()) {
            putToCache(entity, changeFields);
        }
        // fire change event manually
        sendEntityUpdateNotification(entity, ItemAction.Update);
    }

    @Override
    public <T extends HasEntityIdentifier> void save(T entity) {
        BaseCrudRepository pureRepository = (BaseCrudRepository) pureRepositories.get(entity.getClass().getSimpleName());
        pureRepository.save(entity);
    }

    @Override
    public <T extends BaseEntity> T save(T entity, boolean fireNotifyListeners) {
        AbstractRepository foundRepo = classFinder.getRepositoryByClass(entity.getClass());
        final AbstractRepository repository = foundRepo == null && entity instanceof DeviceBaseEntity ? allDeviceRepository : foundRepo;
        EntityContextEventImpl.EntityListener entityUpdateListeners = this.event().getEntityUpdateListeners();

        String entityID = entity.getEntityID();
        // for new entities entityID still null
        T oldEntity = entityID == null ? null : getEntity(entityID, false);
        /*entity.getEntityID() == null || !fireNotifyListeners ? null :
        entityUpdateListeners.isRequireFetchOldEntity(entity) ? getEntity(entity.getEntityID(), false) : null;*/

        T updatedEntity = transactionTemplate.execute(status -> {
            T t = (T) repository.save(entity);
            t.afterFetch(this);
            return t;
        });

        if (fireNotifyListeners) {
            if (oldEntity == null) {
                runUpdateNotifyListeners(updatedEntity, oldEntity, entityUpdateListeners, this.event().getEntityCreateListeners());
            } else {
                runUpdateNotifyListeners(updatedEntity, oldEntity, entityUpdateListeners);
            }
        }

        if (StringUtils.isEmpty(entity.getEntityID())) {
            entity.setEntityID(updatedEntity.getEntityID());
            entity.setId(updatedEntity.getId());
        }

        // post save
        cacheService.entityUpdated(entity);

        return updatedEntity;
    }

    @Override
    public <T extends BaseEntity> List<T> findAll(Class<T> clazz) {
        return findAllByRepository((Class<BaseEntity>) clazz);
    }

    @Override
    public <T extends BaseEntity> List<T> findAllByPrefix(String prefix) {
        AbstractRepository<? extends BaseEntity> repository = getRepositoryByPrefix(prefix);
        return findAllByRepository((Class<BaseEntity>) repository.getEntityClass());
    }

    @Override
    public BaseEntity<? extends BaseEntity> delete(String entityId) {
        BaseEntity<? extends BaseEntity> deletedEntity = entityManager.delete(entityId);
        cacheService.clearCache();
        runUpdateNotifyListeners(null, deletedEntity, this.event().getEntityRemoveListeners());
        return deletedEntity;
    }

    @Override
    public AbstractRepository<? extends BaseEntity> getRepositoryByPrefix(String repositoryPrefix) {
        return repositoriesByPrefix.get(repositoryPrefix);
    }

    @Override
    public <T extends BaseEntity> T getEntityByName(String name, Class<T> entityClass) {
        return classFinder.getRepositoryByClass(entityClass).getByName(name);
    }

    @Override
    public <T> T getBean(String beanName, Class<T> clazz) {
        return this.allApplicationContexts.stream()
                                          .filter(c -> c.containsBean(beanName))
                                          .map(c -> c.getBean(beanName, clazz))
                                          .findAny()
                                          .orElseThrow(() -> new NoSuchBeanDefinitionException(beanName));
    }

    @Override
    public <T> T getBean(Class<T> clazz) {
        for (ApplicationContext context : allApplicationContexts) {
            try {
                return context.getBean(clazz);
            } catch (Exception ignore) {
            }
        }
        throw new NoSuchBeanDefinitionException(clazz);
    }

    @Override
    public <T> Collection<T> getBeansOfType(Class<T> clazz) {
        List<T> values = new ArrayList<>();
        for (ApplicationContext context : allApplicationContexts) {
            values.addAll(context.getBeansOfType(clazz).values());
        }
        return values;
    }

    @Override
    public <T> Map<String, T> getBeansOfTypeWithBeanName(Class<T> clazz) {
        Map<String, T> values = new HashMap<>();
        for (ApplicationContext context : allApplicationContexts) {
            values.putAll(context.getBeansOfType(clazz));
        }
        return values;
    }

    @Override
    public <T> Map<String, Collection<T>> getBeansOfTypeByBundles(Class<T> clazz) {
        Map<String, Collection<T>> res = new HashMap<>();
        for (ApplicationContext context : allApplicationContexts) {
            Collection<T> beans = context.getBeansOfType(clazz).values();
            if (!beans.isEmpty()) {
                res.put(context.getId(), beans);
            }
        }
        return res;
    }

    @Override
    public Collection<AbstractRepository> getRepositories() {
        return repositories.values();
    }

    @Override
    public <T> List<Class<? extends T>> getClassesWithAnnotation(
        @NotNull Class<? extends Annotation> annotation) {
        return classFinder.getClassesWithAnnotation(annotation);
    }

    @Override
    public <T> List<Class<? extends T>> getClassesWithParent(@NotNull Class<T> baseClass) {
        return classFinder.getClassesWithParent(baseClass);
    }

    public void addBundle(Map<String, BundleContext> artifactIdContextMap) {
        for (String artifactId : artifactIdContextMap.keySet()) {
            this.addBundle(artifactIdContextMap.get(artifactId), artifactIdContextMap);
        }
        BUNDLE_UPDATE_COUNT++;
    }

    public void removeBundle(String bundleId) {
        InternalBundleContext internalBundleContext = bundles.remove(bundleId);
        if (internalBundleContext != null) {
            this.removeBundle(internalBundleContext.bundleContext);
        }
    }

    public Object getBeanOfBundleBySimpleName(String bundle, String className) {
        InternalBundleContext internalBundleContext = this.bundles.get(bundle);
        if (internalBundleContext == null) {
            throw new NotFoundException("Unable to find bundle <" + bundle + ">");
        }
        Object o = internalBundleContext.fieldTypes.get(className);
        if (o == null) {
            throw new NotFoundException("Unable to find class <" + className + "> in bundle <" + bundle + ">");
        }
        return o;
    }

    public void sendEntityUpdateNotification(Object entity, ItemAction type) {
        if (!(entity instanceof BaseEntity)) {
            return;
        }
        if (type == ItemAction.Remove) {
            ui().removeItem((BaseEntity<?>) entity);
        } else {
            ui().updateItem((BaseEntity<?>) entity);
        }
        if (showEntityState) {
            type.messageEvent.accept(this);
        }
    }

    public List<BaseEntity> findAllBaseEntities() {
        List<BaseEntity> entities = new ArrayList<>();
        entities.addAll(findAll(DeviceBaseEntity.class));
        return entities;
    }

    public <T> List<T> getEntityServices(Class<T> serviceClass) {
        return allDeviceRepository.listAll().stream()
                                  .filter(e -> serviceClass.isAssignableFrom(e.getClass()))
                                  .map(e -> (T) e)
                                  .collect(Collectors.toList());
    }

    public BaseEntity<?> copyEntity(BaseEntity entity) {
        entity.copy();
        BaseEntity<?> saved = save(entity, true);
        cacheService.clearCache();
        return saved;
    }

    public void fireAllBroadcastLock(Consumer<BroadcastLockManagerImpl> handler) {
        this.workspaceService.fireAllBroadcastLock(handler);
    }

    public <T> T getEnv(String key, Class<T> classType, T defaultValue) {
        return environment.getProperty(key, classType, defaultValue);
    }

    private void initialiseInlineBundles(ApplicationContext applicationContext) {
        log.info("Initialize bundles...");
        ArrayList<BundleEntrypoint> bundleEntrypoints = new ArrayList<>(applicationContext.getBeansOfType(BundleEntrypoint.class).values());
        Collections.sort(bundleEntrypoints);
        for (BundleEntrypoint bundleEntrypoint : bundleEntrypoints) {
            this.bundles.put(bundleEntrypoint.getBundleId(), new InternalBundleContext(bundleEntrypoint, null));
            log.info("Init bundle: <{}>", bundleEntrypoint.getBundleId());
            try {
                bundleEntrypoint.init();
                bundleEntrypoint.onContextRefresh();
            } catch (Exception ex) {
                log.fatal("Unable to init bundle: " + bundleEntrypoint.getBundleId(), ex);
                throw ex;
            }
        }
        log.info("Done initialize bundles");
    }

    private void putToCache(HasEntityIdentifier entity, Map<String, Object[]> changeFields) {
        PureRepository repository;
        if (entity instanceof BaseEntity) {
            repository = classFinder.getRepositoryByClass(((BaseEntity) entity).getClass());
        } else {
            repository = pureRepositories.get(entity.getClass().getSimpleName());
        }
        cacheService.putToCache(repository, entity, changeFields);
    }

    private <T extends HasEntityIdentifier> void runUpdateNotifyListeners(@Nullable T updatedEntity, T oldEntity,
        EntityContextEventImpl.EntityListener... entityListeners) {
        if (updatedEntity != null || oldEntity != null) {
            bgp().builder("entity-" + (updatedEntity == null ? oldEntity : updatedEntity).getEntityID() + "-updated").hideOnUI(true)
                 .execute(() -> {
                     for (EntityContextEventImpl.EntityListener entityListener : entityListeners) {
                         entityListener.notify(updatedEntity, oldEntity);
                     }
                 });
        }
    }

    private <T extends BaseEntity> List<T> findAllByRepository(Class<BaseEntity> clazz) {
        if (clazz.isAnnotationPresent(DisableCacheEntity.class)) {
            return getRepository(clazz).listAll();
        }
        return entityManager.getEntityIDsByEntityClassFullName(clazz).stream().map(entityID -> {
            T entity = entityManager.getEntityWithFetchLazy(entityID);
            if (entity != null) {
                entity.afterFetch(this);
            }
            return entity;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private void removeBundle(BundleContext bundleContext) {
        if (!bundleContext.isInternal() && bundleContext.isInstalled()) {
            ApplicationContext context = bundleContext.getApplicationContext();
            context.getBean(BundleEntrypoint.class).destroy();
            this.allApplicationContexts.remove(context);

            this.cacheService.clearCache();

            rebuildAllRepositories(bundleContext.getApplicationContext(), false);
            updateBeans(bundleContext, bundleContext.getApplicationContext(), false);
            BUNDLE_UPDATE_COUNT++;
        }
    }

    private void addBundle(BundleContext bundleContext, Map<String, BundleContext> artifactIdToContextMap) {
        if (!bundleContext.isInternal() && !bundleContext.isInstalled()) {
            if (!bundleContext.isLoaded()) {
                ui().addBellErrorNotification("fail-bundle-" + bundleContext.getBundleID(),
                    bundleContext.getBundleFriendlyName(), "Unable to load bundle");
                return;
            }
            allApplicationContexts.add(bundleContext.getApplicationContext());
            bundleContext.setInstalled(true);
            for (String bundleDependency : bundleContext.getDependencies()) {
                addBundle(artifactIdToContextMap.get(bundleDependency), artifactIdToContextMap);
            }
            ApplicationContext context = bundleContext.getApplicationContext();

            this.cacheService.clearCache();

            HardwareUtils.copyResources(bundleContext.getBundleClassLoader().getResource("external_files.7z"));

            rebuildAllRepositories(context, true);
            updateBeans(bundleContext, context, true);

            for (BundleEntrypoint bundleEntrypoint :
                context.getBeansOfType(BundleEntrypoint.class).values()) {
                log.info("Init bundle: <{}>", bundleEntrypoint.getBundleId());
                bundleEntrypoint.init();
                this.bundles.put(bundleEntrypoint.getBundleId(), new InternalBundleContext(bundleEntrypoint, bundleContext));
            }
        }
    }

    @SneakyThrows
    private void updateBeans(
        BundleContext bundleContext, ApplicationContext context, boolean addBundle) {
        log.info("Starting update all app bundles");
        Lang.clear();
        fetchSettingPlugins(bundleContext, addBundle);

        for (Class<? extends ContextRefreshed> beanUpdateClass : BEAN_CONTEXT_REFRESH) {
            applicationContext.getBean(beanUpdateClass).onContextRefresh();
        }

        if (bundleContext != null) {
            applicationContext.getBean(ExtRequestMappingHandlerMapping.class).updateContextRestControllers(context, addBundle);
        }

        registerUpdatableSettings(context);

        // fetch entities fires load services if any
        log.info("Loading entities and initialise all related services");
        for (BaseEntity baseEntity : findAllBaseEntities()) {
            if (baseEntity instanceof BaseFileSystemEntity) {
                ((BaseFileSystemEntity<?, ?>) baseEntity).getFileSystem(this).restart(false);
            }
        }

        log.info("Finish update all app bundles");
    }

    private void rebuildAllRepositories(ApplicationContext context, boolean addBundle) {
        Map<String, PureRepository> pureRepositoryMap = context.getBeansOfType(PureRepository.class).values().stream()
                                                               .collect(Collectors.toMap(r -> r.getEntityClass().getSimpleName(), r -> r));

        if (addBundle) {
            pureRepositories.putAll(pureRepositoryMap);
            repositories.putAll(context.getBeansOfType(AbstractRepository.class));
        } else {
            pureRepositories.keySet().removeAll(pureRepositoryMap.keySet());
            repositories.keySet().removeAll(context.getBeansOfType(AbstractRepository.class).keySet());
        }
        baseEntityNameToClass = classFinder.getClassesWithParent(BaseEntity.class).stream().collect(Collectors.toMap(Class::getSimpleName, s -> s));

        rebuildRepositoryByPrefixMap();
    }

    private void registerUpdatableSettings(ApplicationContext context)
        throws IllegalAccessException {
        for (String name : context.getBeanDefinitionNames()) {
            if (!name.contains(".")) {
                Object bean = context.getBean(name);
                Object proxy = this.getTargetObject(bean);
                for (Field field : FieldUtils.getFieldsWithAnnotation(proxy.getClass(), UpdatableSetting.class)) {
                    Class<?> settingClass = field.getDeclaredAnnotation(UpdatableSetting.class).value();
                    Class valueType = ((SettingPlugin) CommonUtils.newInstance(settingClass)).getType();
                    Object value = entityContextSetting.getObjectValue(settingClass);
                    UpdatableValue<Object> updatableValue = UpdatableValue.ofNullable(value, proxy.getClass().getSimpleName() + "_" + field.getName(),
                        valueType);
                    entityContextSetting.listenObjectValue(settingClass, updatableValue.getName(), updatableValue::update);

                    FieldUtils.writeField(field, proxy, updatableValue, true);
                }
            }
        }
    }

    private Object getTargetObject(Object proxy) throws BeansException {
        if (AopUtils.isJdkDynamicProxy(proxy)) {
            try {
                return ((Advised) proxy).getTargetSource().getTarget();
            } catch (Exception e) {
                throw new FatalBeanException("Error getting target of JDK proxy", e);
            }
        }
        return proxy;
    }

    private void rebuildRepositoryByPrefixMap() {
        repositoriesByPrefix = new HashMap<>();
        for (Class<? extends BaseEntity> baseEntity : baseEntityNameToClass.values()) {
            repositoriesByPrefix.put(CommonUtils.newInstance(baseEntity).getEntityPrefix(), getRepository(baseEntity));
        }
    }

    private void fetchSettingPlugins(BundleContext bundleContext, boolean addBundle) {
        if (bundleContext != null) {
            setting().fetchSettingPlugins(bundleContext.getBasePackage(), classFinder, addBundle);
        }
    }

    private void createTableIndexes() {
        List<Class<? extends BaseEntity>> list = classFinder.getClassesWithParent(BaseEntity.class).stream()
                                                            .filter(
                                                                l -> !(WidgetBaseEntity.class.isAssignableFrom(l) || DeviceBaseEntity.class.isAssignableFrom(
                                                                    l)))
                                                            .collect(Collectors.toList());
        list.add(DeviceBaseEntity.class);
        list.add(WidgetBaseEntity.class);

        javax.persistence.EntityManager em = applicationContext.getBean(javax.persistence.EntityManager.class);
        MetamodelImplementor meta = (MetamodelImplementor) em.getMetamodel();
        new TransactionTemplate(transactionManager).execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(
                TransactionStatus transactionStatus) {
                for (Class<? extends BaseEntity> aClass : list) {
                    String tableName = ((Joinable) meta.entityPersister(aClass)).getTableName();
                    try {
                        em.createNativeQuery(String.format(CREATE_TABLE_INDEX, aClass.getSimpleName(), tableName)).executeUpdate();
                    } catch (Exception ex) {
                        log.error("Error while creating index for table: <{}>", tableName, ex);
                    }
                }
            }
        });
    }

    private String getInternalIpAddress() {
        return defaultString(InternalUtil.checkUrlAccessible(),
            applicationContext.getBean(NetworkHardwareRepository.class).getIPAddress());
    }

    @AllArgsConstructor
    public enum ItemAction {
        Insert(context -> context.ui().sendInfoMessage("TOASTR.ENTITY_INSERTED")),
        Update(context -> context.ui().sendInfoMessage("TOASTR.ENTITY_UPDATED")),
        Remove(context -> context.ui().sendWarningMessage("TOASTR.ENTITY_REMOVED"));

        private final Consumer<EntityContextImpl> messageEvent;
    }

    public static class InternalBundleContext {

        @Getter private final BundleEntrypoint bundleEntrypoint;
        @Getter private final BundleContext bundleContext;
        private final Map<String, Object> fieldTypes = new HashMap<>();

        public InternalBundleContext(BundleEntrypoint bundleEntrypoint, BundleContext bundleContext) {
            this.bundleEntrypoint = bundleEntrypoint;
            this.bundleContext = bundleContext;
            if (bundleContext != null) {
                for (WidgetBaseTemplate widgetBaseTemplate :
                    bundleContext
                        .getApplicationContext()
                        .getBeansOfType(WidgetBaseTemplate.class)
                        .values()) {
                    fieldTypes.put(widgetBaseTemplate.getClass().getSimpleName(), widgetBaseTemplate);
                }
            }
        }
    }
}

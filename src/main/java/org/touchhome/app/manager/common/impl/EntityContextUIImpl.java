package org.touchhome.app.manager.common.impl;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.touchhome.common.util.CommonUtils.OBJECT_MAPPER;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.touchhome.app.builder.ui.UIInputBuilderImpl;
import org.touchhome.app.builder.ui.UIInputEntityActionHandler;
import org.touchhome.app.config.WebSocketConfig;
import org.touchhome.app.manager.common.EntityContextImpl;
import org.touchhome.app.model.entity.SettingEntity;
import org.touchhome.app.model.entity.widget.WidgetBaseEntity;
import org.touchhome.app.model.rest.DynamicUpdateRequest;
import org.touchhome.app.notification.BellNotification;
import org.touchhome.app.notification.HeaderButtonNotification;
import org.touchhome.app.notification.ProgressNotification;
import org.touchhome.app.repository.SettingRepository;
import org.touchhome.bundle.api.EntityContextUI;
import org.touchhome.bundle.api.console.ConsolePlugin;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.exception.ProhibitedExecution;
import org.touchhome.bundle.api.model.ActionResponseModel;
import org.touchhome.bundle.api.setting.SettingPluginButton;
import org.touchhome.bundle.api.setting.SettingPluginStatus;
import org.touchhome.bundle.api.ui.UISidebarMenu;
import org.touchhome.bundle.api.ui.action.UIActionHandler;
import org.touchhome.bundle.api.ui.dialog.DialogModel;
import org.touchhome.bundle.api.ui.field.action.HasDynamicContextMenuActions;
import org.touchhome.bundle.api.ui.field.action.v1.UIInputBuilder;
import org.touchhome.bundle.api.ui.field.action.v1.UIInputEntity;
import org.touchhome.bundle.api.util.NotificationLevel;
import org.touchhome.common.util.FlowMap;
import org.touchhome.common.util.Lang;

@Log4j2
@RequiredArgsConstructor
public class EntityContextUIImpl implements EntityContextUI {

    public static final Map<String, ConsolePlugin<?>> customConsolePlugins = new HashMap<>();
    public static final Map<String, ConsolePlugin<?>> consolePluginsMap = new HashMap<>();

    public static final Set<String> customConsolePluginNames = new HashSet<>();

    private final Map<DynamicUpdateRequest, DynamicUpdateContext> dynamicUpdateRegisters = new ConcurrentHashMap<>();
    private final Map<String, DialogModel> dialogRequest = new ConcurrentHashMap<>();
    private final Map<String, BellNotification> bellNotifications = new ConcurrentHashMap<>();
    private final Map<String, HeaderButtonNotification> headerButtonNotifications = new ConcurrentHashMap<>();
    private final Map<String, ProgressNotification> progressMap = new ConcurrentHashMap<>();
    // constructor parameters
    @Getter private final EntityContextImpl entityContext;
    private final SimpMessagingTemplate messagingTemplate;
    private Map<String, BellNotification> bundleEntrypointNotifications = new HashMap<>();

    public void onContextCreated() {
        // run hourly script to drop not used dynamicUpdateRegisters
        entityContext
            .bgp()
            .builder("drop-outdated-dynamicContext")
            .delay(Duration.ofHours(1))
            .interval(Duration.ofHours(1))
            .execute(() ->
                this.dynamicUpdateRegisters.values().removeIf(v -> TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - v.timeout) > 1));
    }

    @Override
    public void addBellNotification(
        @NotNull String entityID,
        @NotNull String name,
        @NotNull String value,
        @NotNull NotificationLevel level,
        @Nullable Consumer<UIInputBuilder> actionSupplier) {
        BellNotification bellNotification = new BellNotification(entityID).setValue(value).setTitle(name).setLevel(level);
        if (actionSupplier != null) {
            UIInputBuilder uiInputBuilder = entityContext.ui().inputBuilder();
            actionSupplier.accept(uiInputBuilder);
            bellNotification.setActions(uiInputBuilder.buildAll());
        }
        bellNotifications.put(entityID, bellNotification);
        sendGlobal(GlobalSendType.bell, entityID, value, name, OBJECT_MAPPER.createObjectNode().put("level", level.name()));
    }

    public void registerForUpdates(DynamicUpdateRequest request) {
        DynamicUpdateContext context = dynamicUpdateRegisters.get(request);
        if (context == null) {
            dynamicUpdateRegisters.put(request, new DynamicUpdateContext());
        } else {
            context.timeout = System.currentTimeMillis(); // refresh timer
            context.registerCounter.incrementAndGet();
        }
        entityContext.event().addEventListener(request.getDynamicUpdateId(), o -> this.sendDynamicUpdate(request, o));
    }

    public void unRegisterForUpdates(DynamicUpdateRequest request) {
        DynamicUpdateContext context = dynamicUpdateRegisters.get(request);
        if (context != null && context.registerCounter.decrementAndGet() == 0) {
            dynamicUpdateRegisters.remove(request);
        }
    }

    public void sendDynamicUpdate(@NotNull String dynamicUpdateId, @Nullable Object value) {
        sendDynamicUpdate(dynamicUpdateId, null, value);
    }

    public void sendDynamicUpdate(
        @NotNull String dynamicUpdateId, @Nullable String entityId, @Nullable Object value) {
        sendDynamicUpdate(new DynamicUpdateRequest(dynamicUpdateId, entityId), value);
    }

    public void sendDynamicUpdate(@NotNull DynamicUpdateRequest request, @Nullable Object value) {
        if (value != null) {
            DynamicUpdateContext context = dynamicUpdateRegisters.get(request);
            if (context != null) {
                if (System.currentTimeMillis() - context.timeout > 60000) {
                    dynamicUpdateRegisters.remove(request);
                } else {
                    sendGlobal(GlobalSendType.dynamicUpdate, null, value, null, OBJECT_MAPPER.createObjectNode().putPOJO("dynamicRequest", request));
                }
            }
        }
    }

    @Override
    public void removeBellNotification(@NotNull String entityID) {
        bellNotifications.remove(entityID);
        sendGlobal(GlobalSendType.bell, entityID, null, null, OBJECT_MAPPER.createObjectNode().put("action", "remove"));
    }

    public void sendAudio(String url) {
        sendGlobal(GlobalSendType.audio, String.valueOf(url.hashCode()), url, null, null);
    }

    @Override
    public UIInputBuilder inputBuilder() {
        return new UIInputBuilderImpl(entityContext);
    }

    @Override
    public <T extends ConsolePlugin<?>> void openConsole(@NotNull T consolePlugin) {
        sendGlobal(GlobalSendType.openConsole, consolePlugin.getEntityID(), null, null, null);
    }

    @Override
    public void reloadWindow(@NotNull String reason) {
        sendGlobal(GlobalSendType.reload, reason, null, null, null);
    }

    @Override
    public void removeItem(@NotNull BaseEntity<?> entity) {
        ObjectNode metadata =
            OBJECT_MAPPER
                .createObjectNode()
                .put("type", "remove")
                .put("entityID", entity.getEntityID())
                .putPOJO("entity", entity);
        sendDynamicUpdate("entity-type-" + entity.getType(), metadata);
    }

    @Override
    public void updateItem(@NotNull BaseEntity<?> entity) {
        updateItem(entity, false);
    }

    @Override
    public void updateItem(
        @NotNull BaseEntity<?> entity, @NotNull String updateField, @Nullable Object value) {
        ObjectNode metadata =
            OBJECT_MAPPER
                .createObjectNode()
                .put("type", "add")
                .putPOJO("entityID", entity.getEntityID())
                .put("updateField", updateField)
                .putPOJO("value", value);

        sendDynamicUpdate("entity-type-" + entity.getType(), metadata);
    }

    @Override
    public void updateInnerSetItem(
        @NotNull BaseEntity<?> parentEntity,
        @NotNull String parentFieldName,
        @NotNull String innerEntityID,
        @NotNull String updateField,
        @NotNull Object value) {
        ObjectNode metadata =
            OBJECT_MAPPER
                .createObjectNode()
                .put("type", "add")
                .put("entityID", parentEntity.getEntityID())
                .put("updateField", updateField)
                .putPOJO("value", value);

        metadata.putPOJO(
            "inner",
            OBJECT_MAPPER
                .createObjectNode()
                .put("id", innerEntityID)
                .put("parentField", parentFieldName));

        sendDynamicUpdate("entity-type-" + parentEntity.getType(), metadata);
    }

    public void updateItem(@NotNull BaseEntity<?> entity, boolean ignoreExtra) {
        ObjectNode metadata =
            OBJECT_MAPPER
                .createObjectNode()
                .put("type", "add")
                .put("entityID", entity.getEntityID())
                .putPOJO("entity", entity);

        if (!ignoreExtra) {
            // insert context actions ixf we need
            if (entity instanceof HasDynamicContextMenuActions) {
                UIInputBuilder uiInputBuilder = inputBuilder();
                ((HasDynamicContextMenuActions) entity).assembleActions(uiInputBuilder);
                metadata.putPOJO("actions", uiInputBuilder.buildAll());
                /* TODO: if (actions != null && !actions.isEmpty()) {
                   metadata.put("actions", actions.stream().map(UIActionResponse::new).collect(Collectors.toSet()));
                }*/
            }
        }
        String entityType = entity instanceof WidgetBaseEntity ? "widget" : entity.getType();
        sendDynamicUpdate("entity-type-" + entityType, metadata);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public <T extends BaseEntity> void sendEntityUpdated(T entity) {
        entityContext.sendEntityUpdateNotification(entity, EntityContextImpl.ItemAction.Update);
    }

    @Override
    public void progress(
        @NotNull String key, double progress, @Nullable String message, boolean cancellable) {
        if (progress >= 100) {
            progressMap.remove(key);
        } else {
            progressMap.put(key, new ProgressNotification(key, progress, message, cancellable));
        }
        sendGlobal(GlobalSendType.progress, key, Math.round(progress), message, cancellable ? OBJECT_MAPPER.createObjectNode().put("cancellable", true) : null);
    }

    @Override
    public void sendDialogRequest(@NotNull DialogModel dialogModel) {
        dialogRequest.computeIfAbsent(
            dialogModel.getEntityID(),
            key -> {
                if (StringUtils.isNotEmpty(dialogModel.getHeaderButtonAttachTo())) {
                    HeaderButtonNotification notificationModel = headerButtonNotifications.get(dialogModel.getHeaderButtonAttachTo());
                    if (notificationModel != null) {
                        notificationModel.getDialogs().add(dialogModel);
                    }
                }

                if (dialogModel.getMaxTimeoutInSec() > 0) {
                    entityContext
                        .bgp()
                        .builder(key + "-dialog-timeout")
                        .delay(Duration.ofSeconds(dialogModel.getMaxTimeoutInSec()))
                        .execute(() -> handleDialog(key, DialogResponseType.Timeout, null, null));
                }

                sendGlobal(GlobalSendType.dialog, key, dialogModel, null, null);

                return dialogModel;
            });
    }

    @Override
    public void sendNotification(@NotNull String destination, @NotNull String value) {
        messagingTemplate.convertAndSend(WebSocketConfig.DESTINATION_PREFIX + destination, value);
    }

    @Override
    public void sendNotification(@NotNull String destination, @NotNull ObjectNode param) {
        messagingTemplate.convertAndSend(WebSocketConfig.DESTINATION_PREFIX + destination, param);
    }

    @Override
    public HeaderButtonBuilder headerButtonBuilder(@NotNull String entityID) {
        return new HeaderButtonBuilder() {
            private final HeaderButtonNotification builder = new HeaderButtonNotification(entityID);

            @Override
            public HeaderButtonBuilder title(@Nullable String title) {
                builder.setTitle(title);
                return this;
            }

            @Override
            public HeaderButtonBuilder icon(
                @Nullable String icon, @Nullable String iconColor, boolean rotate) {
                builder.setIcon(icon).setIconRotate(rotate).setIconColor(iconColor);
                return this;
            }

            @Override
            public HeaderButtonBuilder border(int width, String color) {
                builder.setBorderWidth(width);
                builder.setBorderColor(color);
                return this;
            }

            @Override
            public HeaderButtonBuilder duration(int duration) {
                builder.setDuration(duration);
                return this;
            }

            @Override
            public HeaderButtonBuilder availableForPage(@NotNull Class<? extends BaseEntity> page) {
                if (!page.isAnnotationPresent(UISidebarMenu.class)) {
                    throw new IllegalArgumentException(
                        "Trying add header button to page without annotation UISidebarMenu");
                }
                builder.setPage(
                    StringUtils.defaultIfEmpty(
                        page.getDeclaredAnnotation(UISidebarMenu.class).overridePath(),
                        page.getSimpleName()));
                return this;
            }

            @Override
            public HeaderButtonBuilder clickAction(
                @NotNull Class<? extends SettingPluginButton> clickAction) {
                builder.setClickAction(
                    () -> {
                        entityContext.setting().setValue(clickAction, null);
                        return null;
                    });
                return this;
            }

            @Override
            public HeaderButtonBuilder clickAction(
                @NotNull Supplier<ActionResponseModel> clickAction) {
                builder.setClickAction(clickAction);
                return this;
            }

            @Override
            public void build() {
                HeaderButtonNotification existedModel = headerButtonNotifications.get(entityID);
                // preserve confirmations
                if (existedModel != null) {
                    builder.getDialogs().addAll(existedModel.getDialogs());
                }
                builder.setHandleActionID(entityID);
                headerButtonNotifications.put(entityID, builder);
                sendHeaderButtonToUI(builder, null);
            }
        };
    }

    @Override
    public void removeHeaderButton(
        @NotNull String entityID, @Nullable String icon, boolean forceRemove) {
        HeaderButtonNotification notification = headerButtonNotifications.get(entityID);
        if (notification != null) {
            if (notification.getDialogs().isEmpty()) {
                headerButtonNotifications.remove(entityID);
            } else {
                notification.setIconRotate(false);
                notification.setIcon(icon == null ? notification.getIcon() : icon);
            }
            sendHeaderButtonToUI(
                notification,
                jsonObject -> jsonObject.put("action", forceRemove ? "forceRemove" : "remove"));
        }
    }

    @Override
    public void sendJsonMessage(
        @Nullable String title, @NotNull Object json, @Nullable FlowMap messageParam) {
        title = title == null ? null : Lang.getServerMessage(title, messageParam);
        sendGlobal(GlobalSendType.json, null, json, title, null);
    }

    @Override
    public void sendMessage(
        @Nullable String title, @Nullable String message, @Nullable NotificationLevel level) {
        ObjectNode param = OBJECT_MAPPER.createObjectNode();
        if (level != null) {
            param.put("level", level.name());
        }
        sendGlobal(GlobalSendType.popup, null, message, title, param);
    }

    public void disableHeaderButton(String entityID, boolean disable) {
        sendGlobal(GlobalSendType.headerButton, entityID, null, null, OBJECT_MAPPER.createObjectNode().put("action", "toggle").put("disable", disable));
    }

    private void sendHeaderButtonToUI(
        HeaderButtonNotification notification, Consumer<ObjectNode> additionalSupplier) {
        ObjectNode jsonNode = OBJECT_MAPPER.valueToTree(notification);
        if (additionalSupplier != null) {
            additionalSupplier.accept(jsonNode);
        }
        sendGlobal(GlobalSendType.headerButton, notification.getEntityID(), null, notification.getTitle(), jsonNode);
    }

    void sendGlobal(
        @NotNull GlobalSendType type,
        @Nullable String entityID,
        @Nullable Object value,
        @Nullable String title,
        @Nullable ObjectNode objectNode) {
        if (objectNode == null) {
            objectNode = OBJECT_MAPPER.createObjectNode();
        }
        objectNode.put("entityID", entityID).put("type", type.name());
        if (value != null) {
            objectNode.putPOJO("value", value);
        }
        if (title != null) {
            objectNode.put("title", title);
        }

        sendNotification("-global", objectNode);
    }

    public NotificationResponse getNotifications() {
        long time = System.currentTimeMillis();
        headerButtonNotifications.entrySet().removeIf(
            item -> {
                HeaderButtonNotification json = item.getValue();
                return json.getDuration() != null && time - item.getValue().getCreationTime().getTime() > json.getDuration() * 1000;
            });

        NotificationResponse notificationResponse = new NotificationResponse();
        notificationResponse.dialogs = dialogRequest.values();
        notificationResponse.bellNotifications.addAll(bellNotifications.values());
        notificationResponse.headerButtonNotifications = headerButtonNotifications.values();
        notificationResponse.progress = progressMap.values();

        this.bundleEntrypointNotifications = assembleBundleNotifications();
        notificationResponse.bellNotifications.addAll(this.bundleEntrypointNotifications.values());
        return notificationResponse;
    }

    private void addBellNotification(
        Map<String, BellNotification> map, BellNotification bellNotification) {
        String entityID = bellNotification.getEntityID();
        if (map.containsKey(entityID)) {
            entityID += "~~~" + bellNotification.getCreationTime();
        }
        bellNotification.setEntityID(entityID);
        map.put(entityID, bellNotification);
    }

    private Map<String, BellNotification> assembleBundleNotifications() {
        Map<String, BellNotification> map = new LinkedHashMap<>();
        for (EntityContextImpl.InternalBundleContext bundleContext :
            entityContext.getBundles().values()) {

            bundleContext.getBundleEntrypoint().assembleBellNotifications(
                (notificationLevel, entityID, title, value) ->
                    addBellNotification(map, new BellNotification(entityID).setTitle(title).setValue(value).setLevel(notificationLevel)));

            Class<? extends SettingPluginStatus> statusSettingClass =
                bundleContext.getBundleEntrypoint().getBundleStatusSetting();
            if (statusSettingClass != null) {
                String bundleID = SettingRepository.getSettingBundleName(entityContext, statusSettingClass);
                SettingPluginStatus.BundleStatusInfo bundleStatusInfo =
                    entityContext.setting().getValue(statusSettingClass);
                SettingPluginStatus settingPlugin = (SettingPluginStatus) EntityContextSettingImpl.settingPluginsByPluginKey.get(SettingEntity.getKey(statusSettingClass));
                String statusEntityID = bundleID + "-status";
                if (bundleStatusInfo != null) {
                    BellNotification bundleStatusNotification = new BellNotification(statusEntityID)
                        .setLevel(bundleStatusInfo.getLevel())
                        .setTitle(bundleID)
                        .setValue(defaultIfEmpty(bundleStatusInfo.getMessage(), bundleStatusInfo.getStatus().name()));

                    UIInputBuilder uiInputBuilder = entityContext.ui().inputBuilder();
                    settingPlugin.setActions(uiInputBuilder);
                    bundleStatusNotification.setActions(uiInputBuilder.buildAll());

                    addBellNotification(map, bundleStatusNotification);
                }

                List<SettingPluginStatus.BundleStatusInfo> transientStatuses =
                    settingPlugin.getTransientStatuses(entityContext);
                if (transientStatuses != null) {
                    for (SettingPluginStatus.BundleStatusInfo transientStatus : transientStatuses) {
                        BellNotification bundleStatusNotification = new BellNotification(statusEntityID)
                            .setLevel(transientStatus.getLevel())
                            .setTitle(bundleID)
                            .setValue(defaultIfEmpty(transientStatus.getMessage(), transientStatus.getStatus().name()));

                        if (transientStatus.getActionHandler() != null) {
                            UIInputBuilder uiInputBuilder = entityContext.ui().inputBuilder();
                            transientStatus.getActionHandler().accept(uiInputBuilder);
                            bundleStatusNotification.setActions(uiInputBuilder.buildAll());
                        }

                        addBellNotification(map, bundleStatusNotification);
                    }
                }
            }
        }
        return map;
    }

    public void handleDialog(String entityID, DialogResponseType dialogResponseType, String pressedButton, ObjectNode params) {
        DialogModel model = dialogRequest.remove(entityID);
        if (model != null) {
            model.getActionHandler().handle(dialogResponseType, pressedButton, params);
            if (dialogResponseType != DialogResponseType.Timeout && model.getMaxTimeoutInSec() > 0) {
                entityContext.bgp().cancelThread(entityID + "dialog-timeout");
            }

            for (HeaderButtonNotification notificationModel : headerButtonNotifications.values()) {
                if (notificationModel.getDialogs().remove(model) && notificationModel.getDialogs().isEmpty()) {
                    this.removeHeaderButton(notificationModel.getEntityID()); // request to remove header button if no
                    // confirmation exists
                }
            }
        }
    }

    @Override
    public void registerConsolePluginName(@NotNull String name) {
        customConsolePluginNames.add(name);
    }

    @Override
    public <T extends ConsolePlugin> void registerConsolePlugin(
        @NotNull String name, @NotNull T plugin) {
        customConsolePlugins.put(name, plugin);
        consolePluginsMap.put(name, plugin);
    }

    @Override
    public <T extends ConsolePlugin> T getRegisteredConsolePlugin(@NotNull String name) {
        return (T) customConsolePlugins.get(name);
    }

    @Override
    public boolean unRegisterConsolePlugin(@NotNull String name) {
        if (customConsolePlugins.containsKey(name)) {
            customConsolePlugins.remove(name);
            consolePluginsMap.remove(name);
            return true;
        }
        return false;
    }

    public ActionResponseModel handleNotificationAction(String entityID, String actionEntityID, String value) throws Exception {
        BellNotification bellNotification = bellNotifications.get(entityID);
        if (bellNotification == null) {
            bellNotification = this.bundleEntrypointNotifications.get(entityID);
        }
        if (bellNotification == null) {
            HeaderButtonNotification headerButtonNotification = headerButtonNotifications.get(entityID);
            if (headerButtonNotification == null) {
                throw new IllegalArgumentException("Unable to find header notification: <" + entityID + ">");
            }
            return headerButtonNotification.getClickAction().get();
        }
        UIInputEntity action = bellNotification.getActions().stream()
                                               .filter(a -> a.getEntityID().equals(actionEntityID))
                                               .findAny()
                                               .orElseThrow(() -> new IllegalStateException("Unable to find action: <" + entityID + ">"));
        if (action instanceof UIInputEntityActionHandler) {
            UIActionHandler actionHandler = ((UIInputEntityActionHandler) action).getActionHandler();
            if (actionHandler != null) {
                return actionHandler.handleAction(entityContext, new JSONObject().put("value", value));
            }
        }
        throw new RuntimeException("Action: " + entityID + " has incorrect format");
    }

    public void handleResponse(ActionResponseModel response) {
        switch (response.getResponseAction()) {
            case info:
                this.sendInfoMessage(String.valueOf(response.getValue()));
                break;
            case error:
                this.sendErrorMessage(String.valueOf(response.getValue()));
                break;
            case warning:
                this.sendWarningMessage(String.valueOf(response.getValue()));
                break;
            case success:
                this.sendSuccessMessage(String.valueOf(response.getValue()));
                break;
            case files:
                throw new ProhibitedExecution(); // not implemented yet
        }
    }

    enum GlobalSendType {
        popup,
        json,
        setting,
        progress,
        bell,
        headerButton,
        openConsole,
        reload,
        addItem,
        dialog,
        // send audio to play on ui
        audio,
        // next generation
        dynamicUpdate
    }

    @Getter
    public static class NotificationResponse {

        private final Set<BellNotification> bellNotifications = new TreeSet<>();
        private Collection<HeaderButtonNotification> headerButtonNotifications;
        private Collection<ProgressNotification> progress;
        private Collection<DialogModel> dialogs;
    }

    private static class DynamicUpdateContext {

        private final AtomicInteger registerCounter = new AtomicInteger(0);
        private long timeout = System.currentTimeMillis();
    }
}

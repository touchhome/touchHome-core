package org.touchhome.app.manager.common;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.touchhome.app.service.hardware.SystemMessage;
import org.touchhome.app.setting.system.SystemCPUFetchValueIntervalSetting;
import org.touchhome.app.setting.system.SystemCPUHistorySizeSetting;
import org.touchhome.bundle.api.EntityContextBGP;
import org.touchhome.bundle.api.EntityContextSetting;
import org.touchhome.bundle.api.EntityContextSetting.MemSetterHandler;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.entity.HasStatusAndMsg;
import org.touchhome.bundle.api.inmemory.InMemoryDB;
import org.touchhome.bundle.api.inmemory.InMemoryDBService;
import org.touchhome.bundle.api.model.HasEntityIdentifier;
import org.touchhome.bundle.api.model.Status;

@Log4j2
@RequiredArgsConstructor
public class EntityContextStorage {

    public static final OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    public static final Map<String, EntityMemoryData> ENTITY_MEMORY_MAP = new ConcurrentHashMap<>();
    public static final long TOTAL_MEMORY = osBean.getTotalPhysicalMemorySize();
    public static InMemoryDBService<SystemMessage> cpuStorage;
    // constructor parameters
    private final EntityContextImpl entityContext;
    private EntityContextBGP.ThreadContext<Void> hardwareCpuScheduler;

    {
        EntityContextSetting.MEM_HANDLER.set(new MemSetterHandler() {
            @Override
            public void setValue(@NotNull HasEntityIdentifier entity, @NotNull String key, @NotNull String title, @Nullable Object value) {
                setMemValue(entity, key, title, value);
            }

            @Override
            public Object getValue(HasEntityIdentifier entity, String key, Object defaultValue) {
                EntityMemoryData data = ENTITY_MEMORY_MAP.computeIfAbsent(entity.getEntityID(), s -> new EntityMemoryData());
                return data.VALUE_MAP.getOrDefault(key, defaultValue);
            }
        });
    }

    private void setMemValue(@NotNull HasEntityIdentifier entity, @NotNull String key, @NotNull String title, @Nullable Object value) {
        EntityMemoryData data = ENTITY_MEMORY_MAP.computeIfAbsent(entity.getEntityID(), s -> new EntityMemoryData());
        boolean sendUpdateToUI = entity instanceof HasStatusAndMsg && key.startsWith("status");
        if (value == null || (value instanceof String && value.toString().isEmpty())) {
            if (data.VALUE_MAP.remove(key) == null) {
                sendUpdateToUI = false;
            }
        } else {
            Object prevValue = data.VALUE_MAP.put(key, value);
            if (!Objects.equals(value, prevValue)) {
                logUpdatedValue(entity, key, title, value, data);
            } else {
                sendUpdateToUI = false;
            }
        }

        if (sendUpdateToUI) {
            entityContext.ui().updateItem((BaseEntity<?>) entity, key, value);
        }
    }

    private void logUpdatedValue(@NotNull HasEntityIdentifier entity, @NotNull String key, @NotNull String title, @NotNull Object value,
        EntityMemoryData data) {
        if (value instanceof Status) {
            Status status = (Status) value;
            Level level = status == Status.ERROR ? Level.ERROR : Level.DEBUG;
            Object message = data.VALUE_MAP.get(key + "Message");
            if (message == null) {
                LogManager.getLogger(entity.getClass()).log(level, "[{}]: Set {} '{}' status: {}", entity.getEntityID(), entity, title, status);
            } else {
                LogManager.getLogger(entity.getClass())
                          .log(level, "[{}]: Set {} '{}' status: {}. Msg: {}", entity.getEntityID(), entity, title, status, message);
            }
        }
    }

    public void init() {
        initSystemCpuListening();
    }

    private void initSystemCpuListening() {
        cpuStorage = InMemoryDB.getOrCreateService(SystemMessage.class, (long) entityContext.setting().getValue(SystemCPUHistorySizeSetting.class));
        entityContext.setting().listenValue(SystemCPUHistorySizeSetting.class, "listen-cpu-history", size -> cpuStorage.updateQuota((long) size));

        entityContext.setting().listenValueAndGet(SystemCPUFetchValueIntervalSetting.class,
            "hardware-cpu",
            timeout -> {
                if (this.hardwareCpuScheduler != null) {
                    this.hardwareCpuScheduler.cancel();
                }
                this.hardwareCpuScheduler = entityContext.bgp().builder("hardware-cpu").interval(Duration.ofSeconds(timeout)).execute(
                    () -> {
                        SystemMessage systemMessage = new SystemMessage(osBean, TOTAL_MEMORY);
                        cpuStorage.save(systemMessage);
                        entityContext.event().fireEvent("cpu", systemMessage);
                    });
            });
    }

    public void remove(String entityID) {
        ENTITY_MEMORY_MAP.remove(entityID);
    }

    private static class EntityMemoryData {

        private final Map<String, Object> VALUE_MAP = new ConcurrentHashMap<>();
    }
}

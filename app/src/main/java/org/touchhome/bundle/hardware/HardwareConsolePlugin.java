package org.touchhome.bundle.hardware;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.console.ConsolePlugin;
import org.touchhome.bundle.api.hardware.other.LinuxHardwareRepository;
import org.touchhome.bundle.api.hardware.wifi.WirelessHardwareRepository;
import org.touchhome.bundle.api.model.HasEntityIdentifier;
import org.touchhome.bundle.api.model.UserEntity;
import org.touchhome.bundle.api.ui.field.UIField;
import org.touchhome.bundle.cloud.impl.ServerConnectionStatus;
import org.touchhome.bundle.cloud.setting.CloudServerConnectionMessageSetting;
import org.touchhome.bundle.cloud.setting.CloudServerConnectionStatusSetting;
import org.touchhome.bundle.cloud.setting.CloudServerUrlSetting;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.touchhome.bundle.api.model.UserEntity.ADMIN_USER;

@Component
@RequiredArgsConstructor
public class HardwareConsolePlugin implements ConsolePlugin {

    private final EntityContext entityContext;
    private final LinuxHardwareRepository linuxHardwareRepository;
    private final WirelessHardwareRepository wirelessHardwareRepository;

    @Override
    public List<? extends HasEntityIdentifier> drawEntity() {
        UserEntity user = entityContext.getEntity(ADMIN_USER);

        List<HardwarePluginEntity> list = new ArrayList<>();

        if (EntityContext.isLinuxOrDockerEnvironment()) {
            list.add(new HardwarePluginEntity("Cpu load", linuxHardwareRepository.getCpuLoad()));
            list.add(new HardwarePluginEntity("Cpu temperature", linuxHardwareRepository.getCpuTemp()));
            list.add(new HardwarePluginEntity("Memory", linuxHardwareRepository.getMemory()));
            list.add(new HardwarePluginEntity("SD memory", linuxHardwareRepository.getSDCardMemory().toFineString()));
            list.add(new HardwarePluginEntity("Uptime", linuxHardwareRepository.getUptime()));
            String activeNetworkInterface = wirelessHardwareRepository.getActiveNetworkInterface();
            list.add(new HardwarePluginEntity("Internet stat", wirelessHardwareRepository.stat(activeNetworkInterface).toString()));
            list.add(new HardwarePluginEntity("Internet description", wirelessHardwareRepository.getNetworkDescription(activeNetworkInterface).toString()));
        }
        list.add(new HardwarePluginEntity("Ip address", linuxHardwareRepository.getIpAddress()));
        list.add(new HardwarePluginEntity("Device model", linuxHardwareRepository.getDeviceModel()));
        list.add(new HardwarePluginEntity("Cloud status", readCloudStatus()));
        list.add(new HardwarePluginEntity("Cloud keystore", user.getKeystoreDate() == null ? "" : String.valueOf(user.getKeystoreDate().getTime())));
        list.add(new HardwarePluginEntity("Features", getFeatures()));
        Collections.sort(list);

        return list;
    }

    private String getFeatures() {
        return Stream.of(EntityContext.DeviceFeature.values())
                .map(f -> f + ": " + entityContext.getDeviceFeatures().get(f))
                .collect(Collectors.joining("; "));
    }

    private String readCloudStatus() {
        String error = entityContext.getSettingValue(CloudServerConnectionMessageSetting.class);
        ServerConnectionStatus status = entityContext.getSettingValue(CloudServerConnectionStatusSetting.class);
        return status.name() + ". Errors: " + error + ". Url: " + entityContext.getSettingValue(CloudServerUrlSetting.class);
    }

    @Override
    public int order() {
        return 1500;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    private static class HardwarePluginEntity implements HasEntityIdentifier, Comparable<HardwarePluginEntity> {
        @UIField(order = 1)
        private String name;

        @UIField(order = 2)
        private String value;

        @Override
        public String getEntityID() {
            return name;
        }

        @Override
        public int compareTo(@NotNull HardwareConsolePlugin.HardwarePluginEntity o) {
            return this.name.compareTo(o.name);
        }
    }
}

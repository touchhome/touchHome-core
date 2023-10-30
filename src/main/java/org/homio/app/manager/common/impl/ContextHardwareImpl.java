package org.homio.app.manager.common.impl;

import static org.homio.api.util.JsonUtils.OBJECT_MAPPER;
import static org.homio.app.service.LocalBoardService.TOTAL_MEMORY;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.SystemUtils;
import org.homio.api.ContextHardware;
import org.homio.api.util.HardwareUtils;
import org.homio.app.manager.common.ContextImpl;
import org.homio.hquery.ProgressBar;
import org.homio.hquery.hardware.network.NetworkHardwareRepository;
import org.homio.hquery.hardware.other.MachineHardwareRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Log4j2
@RequiredArgsConstructor
public class ContextHardwareImpl implements ContextHardware {

    private final @Getter @Accessors(fluent = true) ContextImpl context;
    private final NetworkHardwareRepository networkHardwareRepository;
    private final MachineHardwareRepository hardwareRepository;
    private final @Getter @Accessors(fluent = true) ContextNetworkImpl network;

    public ContextHardwareImpl(
        ContextImpl context,
        MachineHardwareRepository hardwareRepository,
        NetworkHardwareRepository networkHardwareRepository) {

        this.context = context;
        this.networkHardwareRepository = networkHardwareRepository;
        this.network = new ContextNetworkImpl(context, hardwareRepository);
        this.hardwareRepository = hardwareRepository;
    }

    public void onContextCreated() throws Exception {
        HardwareUtils.APP_ID = context.setting().getEnv("appId", String.valueOf(System.currentTimeMillis()), true);
        HardwareUtils.MACHINE_IP_ADDRESS = networkHardwareRepository.getIPAddress();
        HardwareUtils.RUN_COUNT = context.setting().getEnv("runCount", 1, true);
        context.setting().setEnv("runCount", HardwareUtils.RUN_COUNT + 1);

        network.onContextCreated();
    }

    @Override
    public @NotNull String execute(@NotNull String command) {
        return hardwareRepository.execute(command);
    }

    @Override
    public @NotNull String executeNoErrorThrow(@NotNull String command, int maxSecondsTimeout, @Nullable ProgressBar progressBar) {
        return hardwareRepository.executeNoErrorThrow(command, maxSecondsTimeout, progressBar);
    }

    @Override
    public @NotNull ArrayList<String> executeNoErrorThrowList(@NotNull String command, int maxSecondsTimeout,
                                                              @Nullable ProgressBar progressBar) {
        return hardwareRepository.executeNoErrorThrowList(command, maxSecondsTimeout, progressBar);
    }

    @Override
    public @NotNull String execute(@NotNull String command, @Nullable ProgressBar progressBar) {
        return hardwareRepository.execute(command, progressBar);
    }

    @Override
    public @NotNull String execute(@NotNull String command, int maxSecondsTimeout) {
        return hardwareRepository.execute(command, maxSecondsTimeout);
    }

    @Override
    public @NotNull String execute(@NotNull String command, int maxSecondsTimeout, ProgressBar progressBar) {
        return hardwareRepository.execute(command, maxSecondsTimeout, progressBar);
    }

    @Override
    public boolean isSoftwareInstalled(@NotNull String soft) {
        return hardwareRepository.isSoftwareInstalled(soft);
    }

    @Override
    public @NotNull ContextHardware installSoftware(@NotNull String soft, int maxSecondsTimeout) {
        hardwareRepository.installSoftware(soft, maxSecondsTimeout);
        return this;
    }

    @Override
    public @NotNull ContextHardware installSoftware(@NotNull String soft, int maxSecondsTimeout, ProgressBar progressBar) {
        hardwareRepository.installSoftware(soft, maxSecondsTimeout, progressBar);
        return this;
    }

    @Override
    public @NotNull ContextHardware enableSystemCtl(@NotNull String soft) {
        hardwareRepository.enableSystemCtl(soft);
        return this;
    }

    @Override
    public @NotNull ContextHardware startSystemCtl(@NotNull String soft) {
        hardwareRepository.startSystemCtl(soft);
        return this;
    }

    @Override
    public void stopSystemCtl(@NotNull String soft) {
        hardwareRepository.stopSystemCtl(soft);
    }

    @Override
    public int getServiceStatus(@NotNull String serviceName) {
        return hardwareRepository.getServiceStatus(serviceName);
    }

    @Override
    public void reboot() {
        hardwareRepository.reboot();
    }

    @Override
    @SneakyThrows
    public @NotNull ProcessStat getProcessStat(long pid) {
        if (SystemUtils.IS_OS_WINDOWS) {
            String result = execute("powershell -Command \"(Get-Process -Id " + pid + " | Select-Object WS, CPU) | ConvertTo-Json\"");
            JsonNode jsonNode;
            jsonNode = OBJECT_MAPPER.readValue(result, JsonNode.class);
            long ws = jsonNode.path("WS").asLong(0L);
            double cpu = jsonNode.path("CPU").asDouble(0D);
            double pc = 0;
            if (ws > 0) {
                pc = ((double) ws / TOTAL_MEMORY) * 100;
            }
            return new ProcessStatImpl(cpu, pc, ws);
        } else {
            String[] result = execute("ps -p " + pid + " -o %cpu,%mem,rss --no-headers").trim().split(" ");
            return new ProcessStatImpl(
                Double.parseDouble(result[0].trim()),
                Double.parseDouble(result[1].trim()),
                Long.parseLong(result[2].trim()));
        }
    }

    @Getter
    @AllArgsConstructor
    public static class ProcessStatImpl implements ProcessStat {

        private final double cpuUsage;
        private final double memUsage;
        private final long mem;
    }
}

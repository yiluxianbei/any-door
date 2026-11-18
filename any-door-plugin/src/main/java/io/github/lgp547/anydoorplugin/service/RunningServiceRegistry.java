package io.github.lgp547.anydoorplugin.service;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录 IDEA 内正在运行的 Application/Spring Boot 实例（project 级，不持久化）。
 * 由 ExecutionListener 在进程启动/终止时维护，供 ServiceLocator 按方法定位服务 pid。
 */
public final class RunningServiceRegistry {

    private final Map<Long, RunningService> runningServices = new ConcurrentHashMap<>();

    public static RunningServiceRegistry getInstance(@NotNull Project project) {
        return project.getService(RunningServiceRegistry.class);
    }

    public void register(@NotNull RunningService runningService) {
        runningServices.put(runningService.executionId(), runningService);
    }

    public void remove(long executionId) {
        runningServices.remove(executionId);
    }

    @NotNull
    public List<RunningService> list() {
        return runningServices.values().stream()
                .sorted(Comparator.comparingLong(RunningService::executionId))
                .toList();
    }

    /**
     * @param moduleName   运行配置所在 Module 名，可能为空
     * @param mainClassName 运行配置主类名，可能为空
     */
    public record RunningService(
            long executionId,
            long pid,
            @NotNull String configName,
            @Nullable String moduleName,
            @Nullable String mainClassName
    ) {
    }
}

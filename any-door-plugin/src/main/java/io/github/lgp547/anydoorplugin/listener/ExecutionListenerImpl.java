package io.github.lgp547.anydoorplugin.listener;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import io.github.lgp547.anydoorplugin.service.RunningServiceRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 登记 IDEA 内运行中的 Application/Spring Boot 实例，并按 executionId 在进程结束时移除。
 * 用于打开 AnyDoor 时按「方法所在服务」自动定位 pid。
 */
public class ExecutionListenerImpl implements ExecutionListener {

    private static final Logger log = Logger.getInstance(ExecutionListenerImpl.class);

    @Override
    public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        registerRunningService(env, handler);
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
        removeRunningService(env);
    }

    @Override
    public void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
        removeRunningService(env);
    }

    @Override
    public void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @Nullable Throwable cause) {
        removeRunningService(env);
    }

    private void registerRunningService(@NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        try {
            if (!(env.getRunProfile() instanceof ApplicationConfiguration configuration)) {
                return;
            }
            Long pid = getPid(handler);
            if (pid == null) {
                log.debug("registerRunningService can not get pid, handler: {}", handler.getClass().getName());
                return;
            }
            Module module = configuration.getConfigurationModule().getModule();
            Project project = env.getProject();
            RunningServiceRegistry.getInstance(project).register(new RunningServiceRegistry.RunningService(
                    env.getExecutionId(),
                    pid,
                    configuration.getName(),
                    module == null ? null : module.getName(),
                    configuration.getMainClassName()
            ));
        } catch (Exception e) {
            log.warn("registerRunningService error", e);
        }
    }

    private void removeRunningService(@NotNull ExecutionEnvironment env) {
        try {
            Project project = env.getProject();
            if (project.isDisposed()) {
                return;
            }
            RunningServiceRegistry.getInstance(project).remove(env.getExecutionId());
        } catch (Exception e) {
            log.debug("removeRunningService error", e);
        }
    }

    @Nullable
    private static Long getPid(@NotNull ProcessHandler handler) {
        if (handler instanceof OSProcessHandler osProcessHandler) {
            Process process = osProcessHandler.getProcess();
            if (process != null) {
                return process.pid();
            }
        }
        return null;
    }
}

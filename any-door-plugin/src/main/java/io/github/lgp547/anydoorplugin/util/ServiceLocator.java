package io.github.lgp547.anydoorplugin.util;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import io.github.lgp547.anydoorplugin.service.RunningServiceRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按「方法所在 Module → 依赖它的服务启动类 → 正在运行的实例」定位 Java attach 目标 pid。
 */
public final class ServiceLocator {

    private ServiceLocator() {
    }

    public enum Status {
        /** 方法与任何服务配置都关联不上，调用方应静默回退原有逻辑 */
        UNKNOWN,
        /** 方法所属服务存在，但没有正在运行的实例 */
        NOT_STARTED,
        /** 定位到唯一 pid */
        RESOLVED,
        /** 多实例选择框被取消 */
        CANCELLED
    }

    public record Resolution(@NotNull Status status, @Nullable Long pid, @Nullable String displayName) {

        public static Resolution unknown() {
            return new Resolution(Status.UNKNOWN, null, null);
        }

        public static Resolution notStarted() {
            return new Resolution(Status.NOT_STARTED, null, null);
        }

        public static Resolution cancelled() {
            return new Resolution(Status.CANCELLED, null, null);
        }

        public static Resolution resolved(long pid, @Nullable String displayName) {
            return new Resolution(Status.RESOLVED, pid, displayName);
        }
    }

    private record Candidate(long pid, @NotNull String displayName) {
    }

    private record ServiceDefinition(@NotNull String moduleName, @Nullable String mainClassName) {
    }

    private record Lookup(boolean hasContainingService, @NotNull List<Candidate> candidates) {
    }

    /**
     * 定位方法所在服务的运行实例。可能弹出多实例选择框，需在 EDT 调用（非 EDT 时内部会 invokeAndWait）。
     */
    @NotNull
    public static Resolution resolve(@NotNull Project project, @NotNull PsiMethod psiMethod) {
        Lookup lookup = ReadAction.compute(() -> doLookup(project, psiMethod));

        if (!lookup.hasContainingService()) {
            return Resolution.unknown();
        }
        if (lookup.candidates().isEmpty()) {
            return Resolution.notStarted();
        }
        if (lookup.candidates().size() == 1) {
            Candidate candidate = lookup.candidates().get(0);
            return Resolution.resolved(candidate.pid(), candidate.displayName());
        }
        return choose(project, lookup.candidates());
    }

    private static Lookup doLookup(@NotNull Project project, @NotNull PsiMethod psiMethod) {
        Module methodModule = findMethodModule(project, psiMethod);
        if (methodModule == null) {
            return new Lookup(false, List.of());
        }

        Set<ServiceDefinition> definitions = findContainingServices(project, methodModule);
        if (definitions.isEmpty()) {
            return new Lookup(false, List.of());
        }

        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        for (RunningServiceRegistry.RunningService runningService : RunningServiceRegistry.getInstance(project).list()) {
            if (runningService.moduleName() == null) {
                continue;
            }
            ServiceDefinition runningDefinition = new ServiceDefinition(runningService.moduleName(), runningService.mainClassName());
            if (!definitions.contains(runningDefinition)) {
                continue;
            }
            candidates.putIfAbsent(runningService.pid(), new Candidate(runningService.pid(), buildDisplayName(runningService)));
        }
        return new Lookup(true, List.copyOf(candidates.values()));
    }

    @Nullable
    private static Module findMethodModule(@NotNull Project project, @NotNull PsiMethod psiMethod) {
        PsiFile containingFile = psiMethod.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        VirtualFile virtualFile = containingFile.getVirtualFile();
        if (virtualFile == null || !virtualFile.isValid()) {
            return null;
        }
        return ProjectRootManager.getInstance(project).getFileIndex().getModuleForFile(virtualFile);
    }

    /**
     * 收集所有「依赖方法所在 Module」的 Application/Spring Boot 运行配置定义。
     */
    @NotNull
    private static Set<ServiceDefinition> findContainingServices(@NotNull Project project, @NotNull Module methodModule) {
        RunManager runManager = RunManager.getInstance(project);
        Map<String, RunnerAndConfigurationSettings> settingsById = new LinkedHashMap<>();
        for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
            settingsById.put(settings.getUniqueID(), settings);
        }
        for (RunnerAndConfigurationSettings settings : runManager.getTempConfigurationsList()) {
            settingsById.putIfAbsent(settings.getUniqueID(), settings);
        }

        Set<ServiceDefinition> definitions = new LinkedHashSet<>();
        for (RunnerAndConfigurationSettings settings : settingsById.values()) {
            if (settings.isTemplate()) {
                continue;
            }
            if (!(settings.getConfiguration() instanceof ApplicationConfiguration configuration)) {
                continue;
            }
            Module module = configuration.getConfigurationModule().getModule();
            if (module == null || !dependsOn(module, methodModule)) {
                continue;
            }
            definitions.add(new ServiceDefinition(module.getName(), configuration.getMainClassName()));
        }
        return definitions;
    }

    /**
     * 服务 Module 是否等于方法 Module，或通过 Module 依赖闭包间接依赖它。
     */
    private static boolean dependsOn(@NotNull Module serviceModule, @NotNull Module methodModule) {
        if (serviceModule.equals(methodModule)) {
            return true;
        }
        Set<Module> visited = new HashSet<>();
        Deque<Module> queue = new ArrayDeque<>();
        queue.add(serviceModule);
        while (!queue.isEmpty()) {
            Module current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            for (Module dependency : ModuleRootManager.getInstance(current).getDependencies()) {
                if (dependency.equals(methodModule)) {
                    return true;
                }
                if (!visited.contains(dependency)) {
                    queue.add(dependency);
                }
            }
        }
        return false;
    }

    @NotNull
    private static String buildDisplayName(@NotNull RunningServiceRegistry.RunningService runningService) {
        String moduleName = runningService.moduleName() == null ? "unknown module" : runningService.moduleName();
        return runningService.configName() + " (" + moduleName + ", pid " + runningService.pid() + ")";
    }

    @NotNull
    private static Resolution choose(@NotNull Project project, @NotNull List<Candidate> candidates) {
        String[] options = candidates.stream().map(Candidate::displayName).toArray(String[]::new);
        int[] selected = {-1};
        Runnable chooser = () -> selected[0] = Messages.showChooseDialog(
                project,
                "当前方法被多个正在运行的服务包含，请选择要调用的服务",
                "AnyDoor",
                null,
                options,
                options[0]
        );

        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) {
            chooser.run();
        } else {
            application.invokeAndWait(chooser);
        }

        int index = selected[0];
        if (index < 0 || index >= candidates.size()) {
            return Resolution.cancelled();
        }
        Candidate candidate = candidates.get(index);
        return Resolution.resolved(candidate.pid(), candidate.displayName());
    }
}

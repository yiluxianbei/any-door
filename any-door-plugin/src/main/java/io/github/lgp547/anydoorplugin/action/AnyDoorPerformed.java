package io.github.lgp547.anydoorplugin.action;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import io.github.lgp547.anydoorplugin.AnyDoorInfo;
import io.github.lgp547.anydoorplugin.dialog.DataContext;
import io.github.lgp547.anydoorplugin.dialog.MainUI;
import io.github.lgp547.anydoorplugin.dialog.old.TextAreaDialog;
import io.github.lgp547.anydoorplugin.dto.ParamCacheDto;
import io.github.lgp547.anydoorplugin.settings.AnyDoorSettingsState;
import io.github.lgp547.anydoorplugin.util.AnyDoorActionUtil;
import io.github.lgp547.anydoorplugin.util.HttpUtil;
import io.github.lgp547.anydoorplugin.util.ImportNewUtil;
import io.github.lgp547.anydoorplugin.util.JsonUtil;
import io.github.lgp547.anydoorplugin.util.NotifierUtil;
import io.github.lgp547.anydoorplugin.util.ServiceLocator;
import io.github.lgp547.anydoorplugin.util.VmUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 打开任意门，核心执行
 */
public class AnyDoorPerformed {

    private static final String SERVICE_NOT_STARTED_MSG = "方法所在服务未启动";

    public void invoke(Project project, PsiMethod psiMethod, Runnable okAction) {
        MethodInvocationSnapshot method = MethodInvocationSnapshot.capture(psiMethod);


        Optional<AnyDoorSettingsState> anyDoorSettingsStateOpt = AnyDoorSettingsState.getAnyDoorSettingsStateNotExc(project);
        if (anyDoorSettingsStateOpt.isEmpty()) {
            return;
        }

        AnyDoorSettingsState service = anyDoorSettingsStateOpt.get();
        BiConsumer<String, Exception> openExcConsumer = (url, e) -> NotifierUtil.notifyError(project, "call " + url + " error [ " + e.getMessage() + " ]");

        ServiceLocator.Resolution resolution = resolveService(project, service, psiMethod);
        if (resolution.status() == ServiceLocator.Status.CANCELLED) {
            return;
        }
        if (resolution.status() == ServiceLocator.Status.NOT_STARTED) {
            NotifierUtil.notifyError(project, SERVICE_NOT_STARTED_MSG);
        }
        Integer resolvedPid = toIntPid(resolution);

        if (method.parameterTypeNames().isEmpty()) {
            if (resolution.status() == ServiceLocator.Status.NOT_STARTED) {
                return;
            }
            long pid = resolvedPid == null ? defaultPid(service) : resolvedPid;
            String jsonDtoStr = getJsonDtoStr(project, method.className(), method.methodName(), method.parameterTypeNames(), "{}", !service.enableAsyncExecute, null);
            openAnyDoor(project, jsonDtoStr, service, pid, openExcConsumer);
            okAction.run();
        } else {
            String cacheKey = AnyDoorActionUtil.genCacheKey(method.className(), method.methodName(), method.parameterTypeNames());
            if (useNewUI(service, project, method, cacheKey, okAction, resolvedPid)) {
                return;
            }
            TextAreaDialog dialog = new TextAreaDialog(project, String.format("fill method(%s) param", method.methodName()), psiMethod.getParameterList(), service.getCache(cacheKey), service,
                    resolvedPid == null ? null : resolvedPid.longValue());
            dialog.setOkAction(() -> {
                okAction.run();
                if (dialog.isChangePid()) {
                    service.pid = dialog.getPid();
                }
                String text = dialog.getText();
                ParamCacheDto paramCacheDto = new ParamCacheDto(dialog.getRunNum(), dialog.getIsConcurrent(), text);
                service.putCache(cacheKey, paramCacheDto);
                // Change to args for the interface type
                if (method.interfaceType()) {
                    text = JsonUtil.transformedKey(text, method.parameterNameTransformer());
                }
                text = JsonUtil.compressJson(text);
                String jsonDtoStr = getJsonDtoStr(project, method.className(), method.methodName(), method.parameterTypeNames(), text, !service.enableAsyncExecute, paramCacheDto);
                openAnyDoor(project, jsonDtoStr, service, dialog.getPid(), openExcConsumer);
            });
            dialog.show();
        }
    }

    public boolean useNewUI(AnyDoorSettingsState service, Project project, PsiClass psiClass, PsiMethod psiMethod, String cacheKey, Runnable okAction) {
        if (service.enableNewUI) {
            doUseNewUI(service, project, psiClass, psiMethod, cacheKey, okAction, null);
            return true;
        }
        return false;
    }

    private boolean useNewUI(AnyDoorSettingsState service, Project project, MethodInvocationSnapshot method, String cacheKey, Runnable okAction, @Nullable Integer initialPid) {
        if (service.enableNewUI) {
            doUseNewUI(service, project, method, cacheKey, okAction, null, initialPid);
            return true;
        }
        return false;
    }

    public void doUseNewUI(AnyDoorSettingsState service, Project project, PsiClass psiClass, PsiMethod psiMethod, String cacheKey, Runnable okAction, @Nullable Long selectedId) {
        MethodInvocationSnapshot method = MethodInvocationSnapshot.capture(psiClass, psiMethod);

        ServiceLocator.Resolution resolution = resolveService(project, service, psiMethod);
        if (resolution.status() == ServiceLocator.Status.CANCELLED) {
            return;
        }
        if (resolution.status() == ServiceLocator.Status.NOT_STARTED) {
            NotifierUtil.notifyError(project, SERVICE_NOT_STARTED_MSG);
        }
        doUseNewUI(service, project, method, cacheKey, okAction, selectedId, toIntPid(resolution));
    }

    private void doUseNewUI(AnyDoorSettingsState service, Project project, MethodInvocationSnapshot method, String cacheKey, Runnable okAction, @Nullable Long selectedId, @Nullable Integer initialPid) {
        ParamCacheDto cache = service.getCache(cacheKey);
        DataContext instance = DataContext.instance(project);
        MainUI mainUI = new MainUI(method.methodName(), project, instance.getExecuteDataContext(method.className(), method.qualifiedMethodName(), selectedId, cache.content()), initialPid);
        mainUI.setOkAction((text) -> {
            okAction.run();
            if (mainUI.isChangePid()) {
                service.pid = mainUI.getPid().longValue();
            }

            ParamCacheDto paramCacheDto = new ParamCacheDto(mainUI.getRunNum(), mainUI.getIsConcurrent(), text);
            service.putCache(cacheKey, paramCacheDto);

            if (method.interfaceType()) {
                text = JsonUtil.transformedKey(text, method.parameterNameTransformer());
            }
            String jsonDtoStr = getJsonDtoStr(project, method.className(), method.methodName(), method.parameterTypeNames(), text, !service.enableAsyncExecute, paramCacheDto);
            openAnyDoor(project, jsonDtoStr, service, mainUI.getPid(), (url, e) -> NotifierUtil.notifyError(project, "call " + url + " error [ " + e.getMessage() + " ]"));
        });
        mainUI.show();
    }

    /**
     * Spring mvc 模式不需要 pid，直接跳过定位。
     */
    @NotNull
    private static ServiceLocator.Resolution resolveService(Project project, AnyDoorSettingsState service, PsiMethod psiMethod) {
        if (!service.isSelectJavaAttach()) {
            return ServiceLocator.Resolution.unknown();
        }
        return ServiceLocator.resolve(project, psiMethod);
    }

    @Nullable
    private static Integer toIntPid(@NotNull ServiceLocator.Resolution resolution) {
        if (resolution.status() != ServiceLocator.Status.RESOLVED || resolution.pid() == null) {
            return null;
        }
        long pid = resolution.pid();
        if (pid < 0 || pid > Integer.MAX_VALUE) {
            return null;
        }
        return (int) pid;
    }

    private static long defaultPid(@NotNull AnyDoorSettingsState service) {
        return service.pid == null ? -1 : service.pid;
    }

    private static void openAnyDoor(Project project, String jsonDtoStr, AnyDoorSettingsState service, long pid, BiConsumer<String, Exception> errHandle) {
        if (service.isSelectJavaAttach()) {
            String anyDoorJarPath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_ATTACH_JAR);
            String paramPath = project.getBasePath() + "/.idea/any-door/AnyDoorParam.json";
            VmUtil.attachAsync(String.valueOf(pid), anyDoorJarPath, jsonDtoStr, paramPath, errHandle);
        } else {
            HttpUtil.postAsyncByJdk(service.mvcAddress + ":" + service.mvcPort + service.mvcWebPathPrefix + "/any_door/run", jsonDtoStr, errHandle);
        }
    }

    @NotNull
    private static String getJsonDtoStr(Project project, String className, String methodName, List<String> paramTypeNameList, String content, boolean isSync, @Nullable ParamCacheDto paramCacheDto) {
        JsonObject jsonObjectReq = new JsonObject();
        jsonObjectReq.addProperty("content", content);
        jsonObjectReq.addProperty("methodName", methodName);
        jsonObjectReq.addProperty("className", className);
        jsonObjectReq.addProperty("sync", isSync);
        if (paramCacheDto != null) {
            jsonObjectReq.addProperty("num", paramCacheDto.getRunNum());
            jsonObjectReq.addProperty("concurrent", paramCacheDto.getConcurrent());
        }
        jsonObjectReq.add("parameterTypes", JsonUtil.toJsonArray(paramTypeNameList));

        String anyDoorJarPath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_JAR);
        String dependenceJarFilePath = ImportNewUtil.getPluginAgentPath(AnyDoorInfo.ANY_DOOR_ALL_DEPENDENCE_JAR);
        String anyDoorCommonJarPath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_COMMON_JAR);
        jsonObjectReq.add("jarPaths", JsonUtil.toJsonArray(List.of(anyDoorJarPath, dependenceJarFilePath, anyDoorCommonJarPath)));
        jsonObjectReq.addProperty("projectBasePath", project.getBasePath());
        return jsonObjectReq.toString();
    }



}

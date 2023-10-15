package org.homio.app.workspace;

import static org.homio.api.util.Constants.PRIMARY_DEVICE;

import com.pivovarit.function.ThrowingRunnable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.homio.addon.fs.Scratch3FSBlocks;
import org.homio.addon.hardware.Scratch3HardwareBlocks;
import org.homio.addon.http.Scratch3NetworkBlocks;
import org.homio.addon.media.Scratch3AudioBlocks;
import org.homio.addon.media.Scratch3ImageEditBlocks;
import org.homio.addon.ui.Scratch3UIBlocks;
import org.homio.api.AddonEntrypoint;
import org.homio.api.EntityContext;
import org.homio.api.exception.ServerException;
import org.homio.api.util.CommonUtils;
import org.homio.api.workspace.WorkspaceBlock;
import org.homio.api.workspace.WorkspaceEventListener;
import org.homio.api.workspace.scratch.Scratch3Block;
import org.homio.api.workspace.scratch.Scratch3ExtensionBlocks;
import org.homio.app.manager.AddonService;
import org.homio.app.model.entity.WorkspaceEntity;
import org.homio.app.setting.workspace.WorkspaceClearButtonSetting;
import org.homio.app.spring.ContextRefreshed;
import org.homio.app.workspace.block.Scratch3Space;
import org.homio.app.workspace.block.core.Scratch3ControlBlocks;
import org.homio.app.workspace.block.core.Scratch3DataBlocks;
import org.homio.app.workspace.block.core.Scratch3EventsBlocks;
import org.homio.app.workspace.block.core.Scratch3MiscBlocks;
import org.homio.app.workspace.block.core.Scratch3MutatorBlocks;
import org.homio.app.workspace.block.core.Scratch3OperatorBlocks;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class WorkspaceService implements ContextRefreshed {

    private static final Pattern ID_PATTERN = Pattern.compile("[\\w-_]*");

    private static final List<Class<?>> systemScratches =
            Arrays.asList(
                    Scratch3ControlBlocks.class,
                    Scratch3MiscBlocks.class,
                    Scratch3DataBlocks.class,
                    Scratch3EventsBlocks.class,
                    Scratch3OperatorBlocks.class,
                    Scratch3MutatorBlocks.class);

    private static final List<Class<?>> inlineScratches =
            Arrays.asList(
                    Scratch3AudioBlocks.class,
                    Scratch3NetworkBlocks.class,
                    Scratch3HardwareBlocks.class,
                    Scratch3UIBlocks.class,
                    Scratch3FSBlocks.class,
                    Scratch3ImageEditBlocks.class);

    private final Duration TIME_WAIT_OLD_WORKSPACE = Duration.ofSeconds(3);
    private final Set<String> ONCE_EXECUTION_BLOCKS =
            new HashSet<>(Arrays.asList("boolean_link", "group_variable_link"));
    // tab <-> list of top blocks
    private final Map<String, WorkspaceTabHolder> tabs = new HashMap<>();
    @Getter
    private final Set<Scratch3ExtensionImpl> extensions = new HashSet<>();
    // constructor parameters
    private final EntityContext entityContext;
    private final AddonService addonService;
    private Collection<WorkspaceEventListener> workspaceEventListeners;
    private Map<String, Scratch3ExtensionBlocks> scratch3Blocks;

    @Override
    public void onContextRefresh(EntityContext entityContext) {
        scratch3Blocks = this.entityContext.getBeansOfType(Scratch3ExtensionBlocks.class).stream()
                .collect(Collectors.toMap(Scratch3ExtensionBlocks::getId, s -> s));
        workspaceEventListeners = this.entityContext.getBeansOfType(WorkspaceEventListener.class);

        loadExtensions();
        loadWorkspace();
    }

    public boolean isEmpty(String content) {
        if (StringUtils.isEmpty(content)) {
            return true;
        }
        JSONObject target = new JSONObject(content).getJSONObject("target");
        for (String key : new String[]{"comments", "blocks"}) {
            if (target.has(key) && !target.getJSONObject(key).keySet().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public WorkspaceBlock getWorkspaceBlockById(String id) {
        for (WorkspaceTabHolder workspaceTabHolder : this.tabs.values()) {
            if (workspaceTabHolder.blocks.containsKey(id)) {
                return workspaceTabHolder.blocks.get(id);
            }
        }
        return null;
    }

    public void fireAllLock(Consumer<LockManagerImpl> handler) {
        for (WorkspaceTabHolder workspaceTabHolder : tabs.values()) {
            handler.accept(workspaceTabHolder.lockManager);
        }
    }

    public void registerScratch3Extension(Scratch3ExtensionBlocks scratch3ExtensionBlock) {
        initScratch3ExtensionBlocks(scratch3ExtensionBlock);
    }

    @SneakyThrows
    private synchronized void reloadWorkspace(WorkspaceEntity workspaceTab) {
        log.debug("Reloading workspace <{}>...", workspaceTab.getName());

        WorkspaceTabHolder workspaceTabHolder = tabs.remove(workspaceTab.getEntityID());
        if (workspaceTabHolder != null) {
            releaseWorkspaceEntity(workspaceTab, workspaceTabHolder);
            // wait to finish all nested processes if workspace started before
            log.info("Wait workspace {}, {} to able to finish old one", workspaceTab.getTitle(), TIME_WAIT_OLD_WORKSPACE);
            Thread.sleep(TIME_WAIT_OLD_WORKSPACE.toMillis());
        }

        workspaceTabHolder =
                new WorkspaceTabHolder(workspaceTab.getEntityID(), entityContext, scratch3Blocks);
        tabs.put(workspaceTab.getEntityID(), workspaceTabHolder);

        if (StringUtils.isNotEmpty(workspaceTab.getContent())) {
            try {
                parseWorkspace(workspaceTab, workspaceTabHolder);
                workspaceTabHolder.blocks.values()
                        .stream()
                        .filter(workspaceBlock -> workspaceBlock.isTopLevel() && !workspaceBlock.isShadow())
                        .forEach(workspaceBlock -> {
                            if (ONCE_EXECUTION_BLOCKS.contains(workspaceBlock.getOpcode())) {
                                executeOnce(workspaceBlock);
                            } else {
                                this.entityContext
                                        .bgp()
                                        .builder("workspace-" + workspaceBlock.getId())
                                        .tap(workspaceBlock::setThreadContext)
                                        .execute(createWorkspaceThread(workspaceBlock));
                            }
                        });
            } catch (Exception ex) {
                log.error("Unable to initialize workspace: " + ex.getMessage(), ex);
                entityContext.ui().toastr().error("Unable to initialize workspace: " + ex.getMessage(), ex);
            }
        }
    }

    private ThrowingRunnable<Exception> createWorkspaceThread(WorkspaceBlock workspaceBlock) {
        return () -> {
            String name = workspaceBlock.getId();
            log.info("Workspace start thread: <{}>", name);
            try {
                ((WorkspaceBlockImpl) workspaceBlock).handleOrEvaluate();
            } catch (Exception ex) {
                log.warn("Error in workspace thread: <{}>, <{}>", name, CommonUtils.getErrorMessage(ex), ex);
                entityContext.ui().toastr().error("Error in workspace", ex);
            }
            log.info("Workspace thread finished: <{}>", name);
        };
    }

    private void releaseWorkspaceEntity(
            WorkspaceEntity workspaceTab, WorkspaceTabHolder oldWorkspaceTabHolder) {
        oldWorkspaceTabHolder.lockManager.release();

        for (WorkspaceEventListener workspaceEventListener : workspaceEventListeners) {
            workspaceEventListener.release(workspaceTab.getEntityID());
        }

        for (WorkspaceBlockImpl workspaceBlock : oldWorkspaceTabHolder.blocks.values()) {
            workspaceBlock.release();
        }
    }

    private void executeOnce(WorkspaceBlock workspaceBlock) {
        try {
            log.debug("Execute single block: <{}>", workspaceBlock);
            workspaceBlock.handle();
        } catch (Exception ex) {
            log.error("Error while execute single block: <{}>", workspaceBlock, ex);
        }
    }

    private void parseWorkspace(
            WorkspaceEntity workspaceTab, WorkspaceTabHolder workspaceTabHolder) {
        JSONObject jsonObject = new JSONObject(workspaceTab.getContent());
        JSONObject target = jsonObject.getJSONObject("target");

        JSONObject blocks = target.getJSONObject("blocks");

        for (String blockId : blocks.keySet()) {
            JSONObject block = blocks.optJSONObject(blockId);
            if (block == null) {
                continue;
            }

            if (!workspaceTabHolder.blocks.containsKey(blockId)) {
                workspaceTabHolder.blocks.put(blockId, new WorkspaceBlockImpl(blockId, workspaceTabHolder));
            }

            WorkspaceBlockImpl workspaceBlock = workspaceTabHolder.blocks.get(blockId);
            workspaceBlock.setShadow(block.optBoolean("shadow"));
            workspaceBlock.setTopLevel(block.getBoolean("topLevel"));
            workspaceBlock.setOpcode(block.getString("opcode"));
            workspaceBlock.setParent(getOrCreateWorkspaceBlock(workspaceTabHolder, block, "parent"));
            workspaceBlock.setNext(getOrCreateWorkspaceBlock(workspaceTabHolder, block, "next"));

            JSONObject fields = block.optJSONObject("fields");
            if (fields != null) {
                for (String fieldKey : fields.keySet()) {
                    workspaceBlock.getFields().put(fieldKey, fields.getJSONArray(fieldKey));
                }
            }
            JSONObject inputs = block.optJSONObject("inputs");
            if (inputs != null) {
                for (String inputsKey : inputs.keySet()) {
                    workspaceBlock.getInputs().put(inputsKey, inputs.getJSONArray(inputsKey));
                }
            }
        }
    }

    private WorkspaceBlockImpl getOrCreateWorkspaceBlock(
            WorkspaceTabHolder workspaceTabHolder, JSONObject block, String key) {
        if (block.has(key) && !block.isNull(key)) {
            workspaceTabHolder.blocks.putIfAbsent(block.getString(key), new WorkspaceBlockImpl(block.getString(key), workspaceTabHolder));
            return workspaceTabHolder.blocks.get(block.getString(key));
        }
        return null;
    }

    private void loadWorkspace() {
        try {
            reloadWorkspaces();
        } catch (Exception ex) {
            log.error("Unable to load workspace. Looks like workspace has incorrect value", ex);
        }
        entityContext.event().addEntityUpdateListener(
                WorkspaceEntity.class, "workspace-change-listener", this::reloadWorkspace);
        entityContext.event().addEntityRemovedListener(WorkspaceEntity.class, "workspace-remove-listener",
                entity -> tabs.remove(entity.getEntityID()));

        // listen for clear workspace
        entityContext.setting().listenValue(WorkspaceClearButtonSetting.class, "wm-clear-workspace",
                () -> entityContext.findAll(WorkspaceEntity.class)
                        .forEach(entity -> entityContext.save(entity.setContent(""))));
    }

    private void reloadWorkspaces() {
        List<WorkspaceEntity> workspaceTabs = entityContext.findAll(WorkspaceEntity.class);
        if (workspaceTabs.isEmpty()) {
            WorkspaceEntity mainWorkspace = entityContext.getEntity(WorkspaceEntity.class, PRIMARY_DEVICE);
            if (mainWorkspace == null) {
                WorkspaceEntity main = new WorkspaceEntity(PRIMARY_DEVICE, "main");
                main.setLocked(true);
                entityContext.save(main);
            }
        } else {
            for (WorkspaceEntity workspaceTab : workspaceTabs) {
                reloadWorkspace(workspaceTab);
            }
        }
    }

    private void loadExtensions() {
        for (Scratch3ExtensionBlocks scratch3ExtensionBlock : entityContext.getBeansOfType(Scratch3ExtensionBlocks.class)) {
            initScratch3ExtensionBlocks(scratch3ExtensionBlock);
        }
    }

    private void initScratch3ExtensionBlocks(Scratch3ExtensionBlocks scratch3ExtensionBlock) {
        scratch3ExtensionBlock.init();

        if (!ID_PATTERN.matcher(scratch3ExtensionBlock.getId()).matches()) {
            throw new IllegalArgumentException("Wrong Scratch3Extension: <" + scratch3ExtensionBlock.getId() + ">. Must contains [a-z] or '-'");
        }

        if (!systemScratches.contains(scratch3ExtensionBlock.getClass())) {
            AddonEntrypoint addonEntrypoint = addonService.findAddonEntrypoint(scratch3ExtensionBlock.getId());
            if (addonEntrypoint == null && scratch3ExtensionBlock.getId().contains("-")) {
                String tryId = scratch3ExtensionBlock.getId().substring(0, scratch3ExtensionBlock.getId().indexOf("-"));
                addonEntrypoint = addonService.findAddonEntrypoint(tryId);
            }
            int order = Integer.MAX_VALUE;
            if (addonEntrypoint == null) {
                if (!inlineScratches.contains(scratch3ExtensionBlock.getClass())) {
                    throw new ServerException("Unable to find addon context with id: " + scratch3ExtensionBlock.getId());
                }
            }
            Scratch3ExtensionImpl scratch3ExtensionImpl = new Scratch3ExtensionImpl(scratch3ExtensionBlock, order);

            if (!extensions.contains(scratch3ExtensionImpl)) {
                insertScratch3Spaces(scratch3ExtensionBlock);
            }
            extensions.add(scratch3ExtensionImpl);
        }
    }

    private void insertScratch3Spaces(Scratch3ExtensionBlocks scratch3ExtensionBlock) {
        ListIterator scratch3BlockListIterator = scratch3ExtensionBlock.getBlocks().listIterator();
        while (scratch3BlockListIterator.hasNext()) {
            Scratch3Block scratch3Block = (Scratch3Block) scratch3BlockListIterator.next();
            if (scratch3Block.getSpaceCount() > 0) {
                scratch3BlockListIterator.add(new Scratch3Space(scratch3Block.getSpaceCount()));
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class WorkspaceTabHolder {

        private final String tabId;
        private final EntityContext entityContext;
        private final Map<String, Scratch3ExtensionBlocks> scratch3Blocks;
        private final LockManagerImpl lockManager;
        private final Map<String, WorkspaceBlockImpl> blocks = new HashMap<>();

        public WorkspaceTabHolder(
                String tabId,
                EntityContext entityContext,
                Map<String, Scratch3ExtensionBlocks> scratch3Blocks) {
            this.tabId = tabId;
            this.scratch3Blocks = scratch3Blocks;
            this.entityContext = entityContext;
            this.lockManager = new LockManagerImpl(tabId);
        }
    }
}

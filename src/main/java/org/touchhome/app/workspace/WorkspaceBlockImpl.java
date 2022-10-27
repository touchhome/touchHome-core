package org.touchhome.app.workspace;

import com.pivovarit.function.ThrowingRunnable;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.json.JSONArray;
import org.json.JSONObject;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.EntityContextBGP;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.state.RawType;
import org.touchhome.bundle.api.state.State;
import org.touchhome.bundle.api.workspace.BroadcastLockManager;
import org.touchhome.bundle.api.workspace.WorkspaceBlock;
import org.touchhome.bundle.api.workspace.scratch.BlockType;
import org.touchhome.bundle.api.workspace.scratch.MenuBlock;
import org.touchhome.bundle.api.workspace.scratch.Scratch3Block;
import org.touchhome.bundle.api.workspace.scratch.Scratch3ExtensionBlocks;
import org.touchhome.common.exception.ServerException;
import org.touchhome.common.util.CommonUtils;

@Setter
@Log4j2
public class WorkspaceBlockImpl implements WorkspaceBlock {

  private final Map<String, WorkspaceBlockImpl> allBlocks;
  private final Map<String, Scratch3ExtensionBlocks> scratch3Blocks;

  @Getter
  private final String id;

  @Getter
  private EntityContext entityContext;

  @Getter
  private String extensionId;

  @Getter
  private String opcode;

  @Getter
  private WorkspaceBlock next;

  @Getter
  private WorkspaceBlockImpl parent;

  @Getter
  private Map<String, JSONArray> inputs = new HashMap<>();

  @Getter
  private Map<String, JSONArray> fields = new HashMap<>();

  @Getter
  private boolean shadow;

  @Getter
  private boolean topLevel;

  private List<BroadcastLockImpl> acquiredLocks;

  private Map<String, State> values = new HashMap<>();
  private AtomicReference<State> lastChildValue;

  private boolean destroy;
  private List<ThrowingRunnable> releaseListeners;

  private EntityContextBGP.ThreadContext<?> threadContext;

  @Setter
  private BroadcastLockManager broadcastLockManager;

  @Setter
  private String tab;

  WorkspaceBlockImpl(String id, Map<String, WorkspaceBlockImpl> allBlocks, Map<String, Scratch3ExtensionBlocks> scratch3Blocks,
      EntityContext entityContext) {
    this.id = id;
    this.allBlocks = allBlocks;
    this.scratch3Blocks = scratch3Blocks;
    this.entityContext = entityContext;
  }

  @Override
  public void logError(String message, Object... params) {
    log(Level.ERROR, message, params);
    String msg = log.getMessageFactory().newMessage(message, params).getFormattedMessage();
    this.entityContext.ui().sendErrorMessage(msg);
  }

  @Override
  public void logErrorAndThrow(String message, Object... params) {
    String msg = log.getMessageFactory().newMessage(message, params).getFormattedMessage();
    log(Level.ERROR, msg);
    throw new ServerException(msg);
  }

  @Override
  public void logWarn(String message, Object... params) {
    String msg = log.getMessageFactory().newMessage(message, params).getFormattedMessage();
    log(Level.WARN, msg);
    this.entityContext.ui().sendWarningMessage(msg);
  }

  @Override
  public void logInfo(String message, Object... params) {
    log(Level.INFO, message, params);
  }

  private void log(Level level, String message, Object... params) {
    log.log(level, "[" + this.extensionId + " -> " + this.opcode + "] - " + message, params);
  }

  void setOpcode(String opcode) {
    this.extensionId = opcode.contains("_") ? opcode.substring(0, opcode.indexOf("_")) : "";
    this.opcode = opcode.contains("_") ? opcode.substring(opcode.indexOf("_") + 1) : opcode;
  }

  @Override
  public <P> List<P> getMenuValues(String key, MenuBlock menuBlock, Class<P> type, String delimiter) {
    String menuId = this.inputs.get(key).getString(1);
    WorkspaceBlock refWorkspaceBlock = allBlocks.get(menuId);
    String value = refWorkspaceBlock.getField(menuBlock.getName());
    List<String> items = Stream.of(value.split(delimiter)).collect(Collectors.toList());
    List<P> result = new ArrayList<>();
    if (Enum.class.isAssignableFrom(type)) {
      for (P p : type.getEnumConstants()) {
        if (items.contains(((Enum<?>) p).name())) {
          result.add(p);
        }
      }
      return result;
    } else if (String.class.isAssignableFrom(type)) {
      return items.stream().map(item -> (P) item).collect(Collectors.toList());
    } else if (Long.class.isAssignableFrom(type)) {
      return items.stream().map(item -> (P) Long.valueOf(item)).collect(Collectors.toList());
    } else if (BaseEntity.class.isAssignableFrom(type)) {
      return items.stream().map(item -> (P) entityContext.getEntity(item)).collect(Collectors.toList());
    }
    logErrorAndThrow("Unable to handle menu value with type: " + type.getSimpleName());
    return null; // unreachable block
  }

  @Override
  public <P> P getMenuValue(String key, MenuBlock menuBlock, Class<P> type) {
    P value = getMenuValueInternal(key, menuBlock, type);
    if (menuBlock instanceof MenuBlock.ServerMenuBlock) {
      MenuBlock.ServerMenuBlock smb = (MenuBlock.ServerMenuBlock) menuBlock;
      if (smb.isRequire() && (value == null || value.toString().isEmpty() || value.toString().equals("-"))) {
        logErrorAndThrow(smb.getFirstKey() + " menu value not found");
      }
    }
    return value;
  }

  private <P> P getMenuValueInternal(String key, MenuBlock menuBlock, Class<P> type) {
    String menuId = this.inputs.get(key).getString(1);
    WorkspaceBlock refWorkspaceBlock = allBlocks.get(menuId);
    String fieldValue = refWorkspaceBlock.getField(menuBlock.getName());
    if (Enum.class.isAssignableFrom(type)) {
      for (P p : type.getEnumConstants()) {
        if (((Enum<?>) p).name().equals(fieldValue)) {
          return p;
        }
      }
    } else if (String.class.isAssignableFrom(type)) {
      return (P) fieldValue;
    } else if (Long.class.isAssignableFrom(type)) {
      return (P) Long.valueOf(fieldValue);
    } else if (BaseEntity.class.isAssignableFrom(type)) {
      return (P) entityContext.getEntity(fieldValue);
    }
    logErrorAndThrow("Unable to handle menu value with type: " + type.getSimpleName());
    return null; // unreachable block
  }

  @Override
  public Path getFile(String key, MenuBlock menuBlock, boolean required) {
    WorkspaceBlock refBlock = getInputWorkspaceBlock(key);
    Path result = null;
    if (refBlock.hasField(menuBlock.getName())) {
      String[] keys = getMenuValue(key, menuBlock, String.class).split("~~~");
      result = Paths.get(keys[keys.length - 1]);
    } else {
      Object evaluate = refBlock.evaluate();
      if (evaluate instanceof RawType) {
        RawType rawType = (RawType) evaluate;
        result = rawType.toPath();
      }
    }
    if (required && (result == null || !Files.isReadable(result))) {
      logErrorAndThrow("Unable to evaluate file for: <{}>", this.opcode);
    }
    return result;
  }

  @Override
  public String findField(Predicate<String> predicate) {
    return fields.keySet().stream().filter(predicate).findAny().orElse(null);
  }

  @Override
  public String getField(String fieldName) {
    return this.fields.get(fieldName).getString(0);
  }

  @Override
  public boolean getFieldBoolean(String fieldName) {
    return this.fields.get(fieldName).getBoolean(0);
  }

  @Override
  public String getFieldId(String fieldName) {
    return this.fields.get(fieldName).optString(1);
  }

  @Override
  public boolean hasField(String fieldName) {
    return this.fields.containsKey(fieldName);
  }

  @Override
  public void setValue(String key, State value) {
    if (key == null) {
      logErrorAndThrow("Trying set value for workspace block with null key");
    }
    this.values.put(key, value);
  }

  @Override
  public void handle() {
    this.handleInternal(scratch3Block -> {
      try {
        scratch3Block.getHandler().handle(this);
      } catch (Exception ex) {
        String err = "Workspace " + scratch3Block.getOpcode() + " scratch error\n" + CommonUtils.getErrorMessage(ex);
        entityContext.ui().sendErrorMessage(err, ex);
        log.error(err);
        return null;
      }
      if (this.next != null && scratch3Block.getBlockType() != BlockType.hat) {
        this.next.handle();
      }
      return null;
    });
  }

  public void handleOrEvaluate() {
    if (getScratch3Block().getHandler() != null) {
      this.handle();
    } else {
      this.evaluate();
    }
  }

  @Override
  public State evaluate() {
    return this.handleInternal(scratch3Block -> {
      try {
        State value = scratch3Block.getEvaluateHandler().handle(this);
        this.setValue("value", value);
        return value;
      } catch (Exception ex) {
        entityContext.ui().sendErrorMessage("Workspace " + scratch3Block.getOpcode() + " scratch error", ex);
        throw new ServerException(ex);
      }
    });
  }

  private State handleInternal(Function<Scratch3Block, State> function) {
    setActiveWorkspace();
    return function.apply(getScratch3Block());
  }

  public void setActiveWorkspace() {
    getNearestLiveThread().setMetadata("activeWorkspaceId", id);
  }

  private EntityContextBGP.ThreadContext<?> getNearestLiveThread() {
    if (this.threadContext != null) {
      return this.threadContext;
    } else if (this.parent != null) {
      return this.parent.getNearestLiveThread();
    }
    throw new RuntimeException("Must be never calls");
  }

  public Scratch3Block getScratch3Block() {
    Scratch3ExtensionBlocks scratch3ExtensionBlocks = scratch3Blocks.get(extensionId);
    if (scratch3ExtensionBlocks == null) {
      logErrorAndThrow(sendScratch3ExtensionNotFound(extensionId));
    } else {
      Scratch3Block scratch3Block = scratch3ExtensionBlocks.getBlocksMap().get(opcode);
      if (scratch3Block == null) {
        logErrorAndThrow(sendScratch3BlockNotFound(extensionId, opcode));
      }
      return scratch3Block;
    }
    // actually unreachable code
    throw new ServerException("unreachable code");
  }

  @Override
  public Integer getInputInteger(String key) {
    return getInputFloat(key).intValue();
  }

  @Override
  public Float getInputFloat(String key, Float defaultValue) {
    return objectToFloat(getInput(key, true), defaultValue);
  }

  public static Float objectToFloat(Object value, Float defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).floatValue();
    }
    try {
      return NumberFormat.getInstance().parse(valueToStr(value, "0")).floatValue();
    } catch (ParseException ex) {
      log.error("Unable to convert value '{}' to float", value);
      return defaultValue;
    }
  }

  @SneakyThrows
  @Override
  public JSONObject getInputJSON(String key, JSONObject defaultValue) {
    Object item = getInput(key, true);
    if (item != null) {
      if (JSONObject.class.isAssignableFrom(item.getClass())) {
        return (JSONObject) item;
      } else if (item instanceof String) {
        return new JSONObject((String) item);
      } else {
        return new JSONObject(CommonUtils.OBJECT_MAPPER.writeValueAsString(item));
      }
    }
    return defaultValue;
  }

  @Override
  public String getInputString(String key, String defaultValue) {
    return valueToStr(getInput(key, true), defaultValue);
  }

  @Override
  public State getValue(String key) {
    if (values.containsKey(key)) {
      return values.get(key);
    }
    return parent == null ? null : parent.getValue(key);
  }

  @Override
  public byte[] getInputByteArray(String key, byte[] defaultValue) {
    Object content = getInput(key, true);
    if (content != null) {
      if (content instanceof State) {
        return ((State) content).byteArrayValue();
      } else if (content instanceof byte[]) {
        return (byte[]) content;
      } else {
        return content.toString().getBytes(Charset.defaultCharset());
      }
    }
    return defaultValue;
  }

  @Override
  public boolean getInputBoolean(String key) {
    Object input = getInput(key, false);
    if (input instanceof Boolean) {
      return (boolean) input;
    }
    return this.allBlocks.get(cast(input)).evaluate().boolValue();
  }

  @Override
  public WorkspaceBlock getInputWorkspaceBlock(String key) {
    return this.allBlocks.get(cast(getInput(key, false)));
  }

  private String cast(Object object) {
    return (String) object;
  }

  @Override
  public Object getInput(String key, boolean fetchValue) {
    JSONArray objects = this.inputs.get(key);
    JSONArray array;

    switch (objects.getInt(0)) {
      case 5: // direct value
        return objects.getString(1);
      case 3: // ref to another block
        String ref;
        // sometimes it may be array, not plain string
        array = objects.optJSONArray(1);
        if (array != null) {
          PrimitiveRef primitiveRef = PrimitiveRef.values()[array.getInt(0)];
          if (fetchValue) {
            return primitiveRef.fetchValue(array, entityContext);
          } else {
            return primitiveRef.getRef(array).toString();
          }
        } else {
          ref = objects.getString(1);
          if (fetchValue) {
            Object evaluateValue = this.allBlocks.get(ref).evaluate();
            this.lastChildValue = new AtomicReference<>(State.of(evaluateValue));
            return evaluateValue;
          }
          return ref;
        }
      case 1:
        array = objects.optJSONArray(1);
        if (array != null) {
          return PrimitiveRef.values()[array.getInt(0)].getRef(array);
        }
        return PrimitiveRef.values()[objects.getInt(0)].getRef(objects);
      case 2: // just a reference
        String reference = objects.getString(1);
        return fetchValue ? allBlocks.get(reference).evaluate() : reference;
      default:
        logErrorAndThrow("Unable to fetch/parse integer value from input with key: " + key);
        return null;
    }
  }

  @Override
  public boolean hasInput(String key) {
    JSONArray objects = this.inputs.get(key);
    if (objects == null) {
      return false;
    }
    switch (objects.getInt(0)) {
      case 5:
        return true;
      case 3:
        return true;
      case 1:
        return !objects.isNull(1);
      case 2:
        return true;
      default:
        logErrorAndThrow("Unable to fetch/parse integer value from input with key: " + key);
        return false;
    }
  }

  @Override
  public String getDescription() {
    return this.opcode;
  }

  public String getTab() {
    return getTopParent().tab;
  }

  public BroadcastLockManager getBroadcastLockManager() {
    return getTopParent().broadcastLockManager;
  }

  @Override
  public void setState(String state) {
    log.info("Set to state: {}", state);
    // getTopParent().threadContext.setState(state);
  }

    /* private WorkspaceBlockImpl getTopParent() {
        WorkspaceBlock cursor = this;
        while (cursor.getParent() != null) {
            cursor = cursor.getParent();
        }
        return (WorkspaceBlockImpl) cursor;
    } */

  @Override
  public boolean isDestroyed() {
    return destroy;
  }

  public void release() {
    this.destroy = true;
    if (this.threadContext != null) {
      this.threadContext.cancel();
    }
    if (this.releaseListeners != null) {
      this.releaseListeners.forEach(t -> {
        try {
          t.run();
        } catch (Exception ex) {
          log.error("Error occurs while release listener: <%s>", ex);
        }
      });
    }
    if (this.parent != null) {
      this.parent.release();
    }
  }

  @Override
  public void onRelease(ThrowingRunnable listener) {
    if (this.releaseListeners == null) {
      this.releaseListeners = new ArrayList<>();
    }
    this.releaseListeners.add(listener);
  }

  private String sendScratch3ExtensionNotFound(String extensionId) {
    String msg = "No scratch extension <" + extensionId + "> found";
    entityContext.ui().sendErrorMessage(msg, extensionId);
    return msg;
  }

  private String sendScratch3BlockNotFound(String extensionId, String opcode) {
    String msg = "No scratch block <" + opcode + "> found in extension <" + extensionId + ">";
    entityContext.ui().sendErrorMessage("workspace.error.scratch_block_not_found", opcode);
    return msg;
  }

  @Override
  public String toString() {
    return "WorkspaceBlockImpl{" + "id='" + id + '\'' + ", extensionId='" + extensionId + '\'' + ", opcode='" + opcode +
        '\'' + '}';
  }

  public void linkBoolean(String variableId) {
    Scratch3Block scratch3Block = getScratch3Block();
    if (scratch3Block.getAllowLinkBoolean() == null) {
      logErrorAndThrow("Unable to link boolean variable to scratch block: " + scratch3Block.getOpcode());
    }
    try {
      scratch3Block.getAllowLinkBoolean().accept(variableId, this);
    } catch (Exception ex) {
      logErrorAndThrow("Error when linking boolean variable to scratch block: " + scratch3Block.getOpcode() +
          CommonUtils.getErrorMessage(ex));
    }
  }

  public void linkVariable(String variableId) {
    Scratch3Block scratch3Block = getScratch3Block();
    if (scratch3Block.getAllowLinkVariable() == null) {
      logErrorAndThrow("Unable to link boolean variable to scratch block: " + scratch3Block.getOpcode());
    }
    scratch3Block.getAllowLinkVariable().accept(variableId, this);
  }

  public State getLastValue() {
    WorkspaceBlockImpl parent = this.parent;
    while (parent != null) {
      State lastValue = parent.getValue("value");
      if (lastValue != null || parent.lastChildValue != null) {
        return lastValue == null ? parent.lastChildValue.get() : lastValue;
      }
      parent = parent.parent;
    }
    return null;
  }

  public void addLock(BroadcastLockImpl broadcastLock) {
    if (acquiredLocks == null) {
      acquiredLocks = new ArrayList<>();
    }
    this.acquiredLocks.add(broadcastLock);
    broadcastLock.addSignalListener(value -> {
      if (value instanceof Collection && ((Collection) value).size() > 1) {
        Collection col = (Collection) value;
        String key = null;
        for (Object item : col) {
          if (key == null) {
            key = (String) item;
          } else {
            this.setValue(key, State.of(item));
            key = null;
          }
        }
      }
      this.setValue("value", State.of(value));
    });
  }

  public void setThreadContext(EntityContextBGP.ThreadContext<?> threadContext) {
    this.threadContext = threadContext;
    threadContext.setMetadata("workspace", id);
    threadContext.setMetadata("activeWorkspaceId", id);
  }

  public void setBroadcastLockManager(BroadcastLockManagerImpl broadcastLockManager) {

  }

  @AllArgsConstructor
  @NoArgsConstructor
  private enum PrimitiveRef {
    UNDEFINED, INPUT_SAME_BLOCK_SHADOW, INPUT_BLOCK_NO_SHADOW, INPUT_DIFF_BLOCK_SHADOW, MATH_NUM_PRIMITIVE,
    POSITIVE_NUM_PRIMITIVE, WHOLE_NUM_PRIMITIVE, INTEGER_NUM_PRIMITIVE,
    CHECKBOX_NUM_PRIMITIVE(array -> array.getBoolean(1), (array, entityContext) -> {
      return array.get(2);
    }), COLOR_PICKER_PRIMITIVE, TEXT_PRIMITIVE, BROADCAST_PRIMITIVE(array -> array.get(2), (array, entityContext) -> {
      return array.get(2);
    }), VAR_PRIMITIVE/*(array -> array.get(2), (array, entityContext) -> {
            WorkspaceStandaloneVariableEntity entity =
                    entityContext.getEntity(WorkspaceStandaloneVariableEntity.PREFIX + array.get(2));
            if (entity == null) {
                throw new IllegalArgumentException("Unable to find variable with name: " + array.get(1));
            }
            return StringUtils.defaultIfEmpty(String.valueOf(entity.getValue()), "0");
        })*/, LIST_PRIMITIVE, FONT_AWESOME_PRIMITIVE;

    private Function<JSONArray, Object> refFn = array -> array.getString(1);

    private BiFunction<JSONArray, EntityContext, Object> valueFn = (array, entityContext) -> array.getString(1);

    public Object getRef(JSONArray array) {
      return refFn.apply(array);
    }

    public Object fetchValue(JSONArray array, EntityContext entityContext) {
      return valueFn.apply(array, entityContext);
    }
  }

  private static String valueToStr(Object content, String defaultValue) {
    if (content != null) {
      if (content instanceof State) {
        return ((State) content).stringValue();
      } else if (content instanceof byte[]) {
        return new String((byte[]) content);
      } else {
        return content.toString();
      }
    }
    return defaultValue;
  }

  private WorkspaceBlockImpl getTopParent() {
    return parent == null ? this : parent.getTopParent();
  }
}

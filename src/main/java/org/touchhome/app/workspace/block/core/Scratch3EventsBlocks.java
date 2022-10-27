package org.touchhome.app.workspace.block.core;

import lombok.Getter;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.workspace.BroadcastLock;
import org.touchhome.bundle.api.workspace.WorkspaceBlock;
import org.touchhome.bundle.api.workspace.scratch.BlockType;
import org.touchhome.bundle.api.workspace.scratch.Scratch3Block;
import org.touchhome.bundle.api.workspace.scratch.Scratch3ExtensionBlocks;

@Getter
@Component
public class Scratch3EventsBlocks extends Scratch3ExtensionBlocks {

  private final Scratch3Block receiveEvent;
  private final Scratch3Block broadcastEvent;

  public Scratch3EventsBlocks(EntityContext entityContext) {
    super("event", entityContext);

    // Blocks
    this.receiveEvent = Scratch3Block.ofHandler("got_broadcast", BlockType.hat, this::receiveEventHandler);
    this.broadcastEvent = Scratch3Block.ofHandler("broadcast", BlockType.command, this::broadcastEventHandler);
  }

  private void broadcastEventHandler(WorkspaceBlock workspaceBlock) {
    fireBroadcastEvent(workspaceBlock.getInputString("BROADCAST_INPUT"));
  }

  @SneakyThrows
  private void receiveEventHandler(WorkspaceBlock workspaceBlock) {
    workspaceBlock.handleNext(next -> {
      String broadcastRefEntityID = workspaceBlock.getFieldId("BROADCAST_OPTION");
      BroadcastLock lock = workspaceBlock.getBroadcastLockManager().getOrCreateLock(workspaceBlock, broadcastRefEntityID);

      workspaceBlock.subscribeToLock(lock, next::handle);
    });
  }

  public void fireBroadcastEvent(String broadcastRefEntityID) {
    entityContext.var().set(broadcastRefEntityID, "event");
  }
}

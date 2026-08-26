package studio.litematicrender.worker;

import net.fabricmc.api.ClientModInitializer;

public final class WorkerClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    WorkerBridge.start();
  }
}

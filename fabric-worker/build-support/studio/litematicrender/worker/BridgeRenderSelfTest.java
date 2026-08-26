package studio.litematicrender.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BridgeRenderSelfTest {
  private BridgeRenderSelfTest() {
  }

  public static void main(String[] args) throws Exception {
    Path root = args.length > 0 ? Paths.get(args[0]).toAbsolutePath().normalize() : Files.createTempDirectory("lrs-bridge-self-test-");
    Files.createDirectories(root);
    Path litematic = root.resolve("fixture.litematic");
    RendererSelfTest.writeFixture(litematic);
    Map<String, Object> command = new LinkedHashMap<String, Object>();
    command.put("protocolVersion", Long.valueOf(1));
    command.put("type", "submit_render_job");
    command.put("job", RendererSelfTest.job(litematic, root.resolve("png")));
    Path session = root.resolve("session");
    Files.createDirectories(session);
    Files.write(session.resolve("commands.jsonl"), (WorkerJson.stringify(command) + "\n").getBytes(StandardCharsets.UTF_8));
    Files.write(session.resolve("events.jsonl"), new byte[0]);
    System.setProperty("lrs.session", session.toString());
    WorkerBridge.start();
    while (true) Thread.sleep(1000L);
  }
}

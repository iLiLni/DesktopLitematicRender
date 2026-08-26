package studio.litematicrender.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class EntityDiagnostics {
  private static Path file;

  private EntityDiagnostics() {
  }

  static synchronized void begin(int rawCount, int blockCount) {
    String rawSession = System.getProperty("lrs.session", "").trim();
    if (rawSession.isEmpty()) return;
    try {
      Path session = Path.of(rawSession).toAbsolutePath().normalize();
      Files.createDirectories(session);
      file = session.resolve("entity-diagnostics.log");
      Files.writeString(file,
        "===== Entity diagnostics =====\n"
          + "session=" + session + "\n"
          + "rawEntities=" + rawCount + "\n"
          + "occupiedBlocks=" + blockCount + "\n",
        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (Exception ignored) {
      file = null;
    }
  }

  static synchronized void frame(LitematicModel.EntityFrame frame, java.util.Map<String, Object> metadata) {
    if (file == null || frame == null) return;
    StringBuilder line = new StringBuilder();
    line.append("projectFrame offset=").append(vector(frame.offset))
      .append(" score=").append(frame.score)
      .append(" candidates=").append(frame.candidateCount)
      .append(" blockBounds=").append(intVector(frame.bounds));
    Object enclosing = metadata == null ? null : metadata.get("EnclosingSize");
    if (enclosing != null) line.append(" metadataEnclosingSize=").append(value(enclosing));
    line.append('\n');
    write(line.toString());
  }

  static synchronized void entity(LitematicModel.RawEntity raw, LitematicModel.EntityPlacement placement, LitematicModel.Entity entity) {
    if (file == null) return;
    StringBuilder line = new StringBuilder();
    line.append("entity id=").append(raw.name)
      .append(" region=").append(raw.region)
      .append(" rawPos=").append(vector(raw.position))
      .append(" regionOrigin=").append(raw.originX).append(',').append(raw.originY).append(',').append(raw.originZ)
      .append(" regionSize=").append(raw.sizeX).append(',').append(raw.sizeY).append(',').append(raw.sizeZ)
      .append(" localCandidate=").append(vector(new double[] { raw.position[0] + raw.originX, raw.position[1] + raw.originY, raw.position[2] + raw.originZ }))
      .append(" absoluteCandidate=").append(vector(raw.position))
      .append(" projectedRaw=").append(vector(new double[] {
        raw.position[0] + placement.globalOffset[0],
        raw.position[1] + placement.globalOffset[1],
        raw.position[2] + placement.globalOffset[2]
      }))
      .append(" localScore=").append(placement.localScore)
      .append(" absoluteScore=").append(placement.rawScore)
      .append(" mode=").append(placement.mode)
      .append(" globalOffset=").append(vector(placement.globalOffset))
      .append(" globalScore=").append(placement.globalScore)
      .append(" tile=").append(intVector(placement.tile))
      .append(" resolved=").append(vector(placement.value))
      .append(" facing=").append(LitematicModel.Entity.facingFromEntityData(raw.data, raw.yaw).id)
      .append(" renderOrigin=").append(vector(new double[] { entity.x - 0.5d, entity.renderBaseY(), entity.z - 0.5d }))
      .append('\n');
    write(line.toString());
  }

  static synchronized void finish(int count) {
    if (file == null) return;
    write("resolvedEntities=" + count + "\n===== End entity diagnostics =====\n");
  }

  private static void write(String text) {
    try {
      Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception ignored) {
    }
  }

  private static String vector(double[] value) {
    if (value == null || value.length < 3) return "[]";
    return "[" + value[0] + "," + value[1] + "," + value[2] + "]";
  }

  private static String intVector(int[] value) {
    if (value == null || value.length == 0) return "[]";
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < value.length; index++) {
      if (index > 0) result.append(',');
      result.append(value[index]);
    }
    return result.append(']').toString();
  }

  private static String value(Object value) {
    if (value == null) return "null";
    if (value instanceof int[]) return intVector((int[]) value);
    return String.valueOf(value);
  }
}

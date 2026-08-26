package studio.litematicrender.worker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LitematicModel {
  static final int MAX_BLOCKS = 1_500_000;

  static final class Block {
    final int x;
    final int y;
    final int z;
    final String name;
    final Map<String, Object> properties;

    Block(int x, int y, int z, String name, Map<String, Object> properties) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.name = name;
      this.properties = properties;
    }

    String key() {
      return LitematicModel.key(x, y, z);
    }
  }

  static final class Entity {
    final double x;
    final double y;
    final double z;
    final float yaw;
    final String name;
    final Map<String, Object> data;
    final String coordinateMode;
    final double[] rawPosition;
    final double localScore;
    final double rawScore;
    final int[] tileCoordinates;
    final double[] globalOffset;
    final double globalScore;

    Entity(double x, double y, double z, float yaw, String name, Map<String, Object> data) {
      this(x, y, z, yaw, name, data, "manual", new double[] { x, y, z }, Double.NaN, Double.NaN, new int[0]);
    }

    Entity(double x, double y, double z, float yaw, String name, Map<String, Object> data, String coordinateMode,
        double[] rawPosition, double localScore, double rawScore, int[] tileCoordinates) {
      this(x, y, z, yaw, name, data, coordinateMode, rawPosition, localScore, rawScore, tileCoordinates,
        new double[] { 0d, 0d, 0d }, Double.NaN);
    }

    Entity(double x, double y, double z, float yaw, String name, Map<String, Object> data, String coordinateMode,
        double[] rawPosition, double localScore, double rawScore, int[] tileCoordinates, double[] globalOffset,
        double globalScore) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.yaw = yaw;
      this.name = name;
      this.data = data;
      this.coordinateMode = coordinateMode;
      this.rawPosition = rawPosition == null ? new double[0] : rawPosition.clone();
      this.localScore = localScore;
      this.rawScore = rawScore;
      this.tileCoordinates = tileCoordinates == null ? new int[0] : tileCoordinates.clone();
      this.globalOffset = globalOffset == null ? new double[] { 0d, 0d, 0d } : globalOffset.clone();
      this.globalScore = globalScore;
    }

    Block renderBlock() {
      LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>(data);
      BlockFace facing = facingFromEntityData(data, yaw);
      properties.put("facing", facing.id);
      Object itemValue = data.get("Item");
      if (itemValue instanceof Map) {
        Object itemId = ((Map<?, ?>) itemValue).get("id");
        if (itemId instanceof String) properties.put("lrs_item_id", itemId);
      }
      Object itemRotation = data.get("ItemRotation");
      if (itemRotation instanceof Number) properties.put("lrs_item_rotation", Integer.valueOf(((Number) itemRotation).intValue()));
      properties.put("lrs_entity", "true");
      return new Block(0, 0, 0, name, properties);
    }

    boolean surfaceMounted() {
      String value = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
      return value.contains("item_frame") || value.contains("painting");
    }

    double renderBaseY() {
      return surfaceMounted() ? y - 0.5d : y;
    }

    static BlockFace facingFromEntityData(Map<String, Object> data, float yaw) {
      Object value = data.get("Facing");
      if (!(value instanceof Number)) value = data.get("Direction");
      if (value instanceof Number) {
        int id = ((Number) value).intValue();
        if (id == 0) return BlockFace.DOWN;
        if (id == 1) return BlockFace.UP;
        if (id == 2) return BlockFace.NORTH;
        if (id == 3) return BlockFace.SOUTH;
        if (id == 4) return BlockFace.WEST;
        if (id == 5) return BlockFace.EAST;
      }
      return facingFromYaw(yaw);
    }

    static BlockFace facingFromYaw(float yaw) {
      int step = Math.floorMod((int) Math.round(yaw / 90f), 4);
      if (step == 0) return BlockFace.SOUTH;
      if (step == 1) return BlockFace.WEST;
      if (step == 2) return BlockFace.NORTH;
      return BlockFace.EAST;
    }
  }

  static final class RawEntity {
    final String region;
    final String name;
    final Map<String, Object> data;
    final double[] position;
    final float yaw;
    final int originX;
    final int originY;
    final int originZ;
    final int sizeX;
    final int sizeY;
    final int sizeZ;

    RawEntity(String region, String name, Map<String, Object> data, double[] position, float yaw,
        int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
      this.region = region;
      this.name = name;
      this.data = data;
      this.position = position;
      this.yaw = yaw;
      this.originX = originX;
      this.originY = originY;
      this.originZ = originZ;
      this.sizeX = sizeX;
      this.sizeY = sizeY;
      this.sizeZ = sizeZ;
    }
  }

  static final class CoordinateChoice {
    final double[] value;
    final String mode;
    final double localScore;
    final double rawScore;

    CoordinateChoice(double[] value, String mode, double localScore, double rawScore) {
      this.value = value;
      this.mode = mode;
      this.localScore = localScore;
      this.rawScore = rawScore;
    }
  }

  static final class EntityPlacement {
    final double[] value;
    final String mode;
    final double localScore;
    final double rawScore;
    final int[] tile;
    final double[] globalOffset;
    final double globalScore;

    EntityPlacement(double[] value, String mode, double localScore, double rawScore, int[] tile) {
      this(value, mode, localScore, rawScore, tile, new double[] { 0d, 0d, 0d }, Double.NaN);
    }

    EntityPlacement(double[] value, String mode, double localScore, double rawScore, int[] tile,
        double[] globalOffset, double globalScore) {
      this.value = value;
      this.mode = mode;
      this.localScore = localScore;
      this.rawScore = rawScore;
      this.tile = tile;
      this.globalOffset = globalOffset == null ? new double[] { 0d, 0d, 0d } : globalOffset.clone();
      this.globalScore = globalScore;
    }
  }

  static final class EntityFrame {
    final double[] offset;
    final double score;
    final int candidateCount;
    final int[] bounds;

    EntityFrame(double[] offset, double score, int candidateCount, int[] bounds) {
      this.offset = offset == null ? new double[] { 0d, 0d, 0d } : offset.clone();
      this.score = score;
      this.candidateCount = candidateCount;
      this.bounds = bounds == null ? new int[0] : bounds.clone();
    }
  }

  final List<Block> blocks;
  final List<Entity> entities;
  final List<Block> framingBlocks;
  final Map<String, Block> occupied;
  final int minX;
  final int minY;
  final int minZ;
  final int maxX;
  final int maxY;
  final int maxZ;
  final int frameMinX;
  final int frameMinY;
  final int frameMinZ;
  final int frameMaxX;
  final int frameMaxY;
  final int frameMaxZ;

  private LitematicModel(List<Block> blocks, List<Entity> entities) {
    if (blocks.isEmpty() && entities.isEmpty()) throw new IllegalArgumentException("投影文件不包含可渲染方块或实体。");
    this.blocks = blocks;
    this.entities = entities;
    this.occupied = new LinkedHashMap<String, Block>(blocks.size() * 2);
    int lowX = Integer.MAX_VALUE;
    int lowY = Integer.MAX_VALUE;
    int lowZ = Integer.MAX_VALUE;
    int highX = Integer.MIN_VALUE;
    int highY = Integer.MIN_VALUE;
    int highZ = Integer.MIN_VALUE;
    for (Block block : blocks) {
      occupied.put(block.key(), block);
      lowX = Math.min(lowX, block.x);
      lowY = Math.min(lowY, block.y);
      lowZ = Math.min(lowZ, block.z);
      highX = Math.max(highX, block.x);
      highY = Math.max(highY, block.y);
      highZ = Math.max(highZ, block.z);
    }
    if (blocks.isEmpty()) {
      for (Entity entity : entities) {
        lowX = Math.min(lowX, (int) Math.floor(entity.x - 0.5d));
        lowY = Math.min(lowY, (int) Math.floor(entity.y));
        lowZ = Math.min(lowZ, (int) Math.floor(entity.z - 0.5d));
        highX = Math.max(highX, (int) Math.ceil(entity.x + 0.5d));
        highY = Math.max(highY, (int) Math.ceil(entity.y + 1.25d));
        highZ = Math.max(highZ, (int) Math.ceil(entity.z + 0.5d));
      }
    }
    minX = lowX;
    minY = lowY;
    minZ = lowZ;
    maxX = highX;
    maxY = highY;
    maxZ = highZ;
    framingBlocks = chooseFramingBlocks(blocks);
    int[] framing = bounds(framingBlocks.isEmpty() ? blocks : framingBlocks, lowX, lowY, lowZ, highX, highY, highZ);
    frameMinX = framing[0];
    frameMinY = framing[1];
    frameMinZ = framing[2];
    frameMaxX = framing[3];
    frameMaxY = framing[4];
    frameMaxZ = framing[5];
  }

  static LitematicModel read(Path file) throws Exception {
    Map<String, Object> root = NbtReader.read(file);
    Map<String, Object> metadata = map(root.get("Metadata"));
    Map<String, Object> regions = map(root.get("Regions"));
    if (regions.isEmpty()) throw new IllegalArgumentException("该文件不是可读取的 Litematic：缺少 Regions。");
    LinkedHashMap<String, Block> blocks = new LinkedHashMap<String, Block>();
    ArrayList<RawEntity> rawEntities = new ArrayList<RawEntity>();
    for (Map.Entry<String, Object> entry : regions.entrySet()) readRegion(entry.getKey(), map(entry.getValue()), blocks, rawEntities);
    ArrayList<Entity> entities = resolveEntities(rawEntities, blocks, metadata);
    return new LitematicModel(new ArrayList<Block>(blocks.values()), entities);
  }

  static LitematicModel of(List<Block> blocks) {
    return new LitematicModel(new ArrayList<Block>(blocks), new ArrayList<Entity>());
  }

  static LitematicModel of(List<Block> blocks, List<Entity> entities) {
    return new LitematicModel(new ArrayList<Block>(blocks), new ArrayList<Entity>(entities));
  }

  boolean contains(int x, int y, int z) {
    return occupied.containsKey(key(x, y, z));
  }

  Block blockAt(int x, int y, int z) {
    return occupied.get(key(x, y, z));
  }

  int width() {
    return maxX - minX + 1;
  }

  int height() {
    return maxY - minY + 1;
  }

  int depth() {
    return maxZ - minZ + 1;
  }

  int frameWidth() {
    return frameMaxX - frameMinX + 1;
  }

  int frameHeight() {
    return frameMaxY - frameMinY + 1;
  }

  int frameDepth() {
    return frameMaxZ - frameMinZ + 1;
  }

  int omittedFramingBlocks() {
    return Math.max(0, blocks.size() - framingBlocks.size());
  }

  private static int[] bounds(List<Block> values, int fallbackMinX, int fallbackMinY, int fallbackMinZ, int fallbackMaxX, int fallbackMaxY, int fallbackMaxZ) {
    if (values.isEmpty()) return new int[] { fallbackMinX, fallbackMinY, fallbackMinZ, fallbackMaxX, fallbackMaxY, fallbackMaxZ };
    int lowX = Integer.MAX_VALUE, lowY = Integer.MAX_VALUE, lowZ = Integer.MAX_VALUE;
    int highX = Integer.MIN_VALUE, highY = Integer.MIN_VALUE, highZ = Integer.MIN_VALUE;
    for (Block block : values) {
      lowX = Math.min(lowX, block.x); lowY = Math.min(lowY, block.y); lowZ = Math.min(lowZ, block.z);
      highX = Math.max(highX, block.x); highY = Math.max(highY, block.y); highZ = Math.max(highZ, block.z);
    }
    return new int[] { lowX, lowY, lowZ, highX, highY, highZ };
  }

  private static List<Block> chooseFramingBlocks(List<Block> values) {
    if (values.size() < 48) return values;
    Map<String, Bucket> buckets = new HashMap<String, Bucket>();
    for (Block block : values) {
      int x = Math.floorDiv(block.x, 8), y = Math.floorDiv(block.y, 8), z = Math.floorDiv(block.z, 8);
      String key = bucketKey(x, y, z);
      Bucket bucket = buckets.get(key);
      if (bucket == null) {
        bucket = new Bucket(x, y, z);
        buckets.put(key, bucket);
      }
      bucket.blocks.add(block);
    }
    if (buckets.size() < 2) return values;
    HashSet<String> visited = new HashSet<String>();
    ArrayList<Block> largest = null;
    for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
      if (!visited.add(entry.getKey())) continue;
      ArrayDeque<Bucket> queue = new ArrayDeque<Bucket>();
      ArrayList<Block> component = new ArrayList<Block>();
      queue.add(entry.getValue());
      while (!queue.isEmpty()) {
        Bucket bucket = queue.removeFirst();
        component.addAll(bucket.blocks);
        for (int dx = -1; dx <= 1; dx++) {
          for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
              String nextKey = bucketKey(bucket.x + dx, bucket.y + dy, bucket.z + dz);
              if (!visited.add(nextKey)) continue;
              Bucket next = buckets.get(nextKey);
              if (next != null) queue.addLast(next);
            }
          }
        }
      }
      if (largest == null || component.size() > largest.size()) largest = component;
    }
    if (largest == null) return values;
    int omitted = values.size() - largest.size();
    return largest.size() * 100 >= values.size() * 96 && omitted >= Math.max(4, values.size() / 100) ? largest : values;
  }

  private static String bucketKey(int x, int y, int z) {
    return x + ":" + y + ":" + z;
  }

  private static final class Bucket {
    final int x;
    final int y;
    final int z;
    final ArrayList<Block> blocks = new ArrayList<Block>();

    Bucket(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  private static void readRegion(String label, Map<String, Object> region, Map<String, Block> output, List<RawEntity> entities) {
    int[] position = ints(region.get("Position"));
    int[] signedSize = ints(region.get("Size"));
    if (position.length < 3 || signedSize.length < 3) {
      throw new IllegalArgumentException("区域 “" + label + "” 的 Position 或 Size 不是可识别的 x/y/z 坐标结构。");
    }
    int sizeX = Math.abs(signedSize[0]);
    int sizeY = Math.abs(signedSize[1]);
    int sizeZ = Math.abs(signedSize[2]);
    if (sizeX == 0 || sizeY == 0 || sizeZ == 0) return;
    long cellCount = (long) sizeX * (long) sizeY * (long) sizeZ;
    if (cellCount > 16_000_000L) throw new IllegalArgumentException("区域 “" + label + "” 过大（" + cellCount + " 格），当前版本不支持。");
    int originX = signedSize[0] < 0 ? position[0] + signedSize[0] + 1 : position[0];
    int originY = signedSize[1] < 0 ? position[1] + signedSize[1] + 1 : position[1];
    int originZ = signedSize[2] < 0 ? position[2] + signedSize[2] + 1 : position[2];
    List<Object> rawPalette = list(region.get("BlockStatePalette"));
    if (rawPalette.isEmpty()) throw new IllegalArgumentException("区域 “" + label + "” 缺少 BlockStatePalette。");
    ArrayList<BlockState> palette = new ArrayList<BlockState>(rawPalette.size());
    for (Object value : rawPalette) {
      Map<String, Object> state = map(value);
      String name = string(state.get("Name"));
      if (name.isEmpty()) name = "minecraft:air";
      palette.add(new BlockState(name, map(state.get("Properties"))));
    }
    long[] packed = longs(region.get("BlockStates"));
    int bits = Math.max(2, bitsForPalette(palette.size()));
    long expectedLongs = ((cellCount * (long) bits) + 63L) >>> 6;
    if (packed.length < expectedLongs) {
      throw new IllegalArgumentException("区域 “" + label + "” 的 BlockStates 数据不完整（需要 " + expectedLongs + " 个 long，实际 " + packed.length + "）。");
    }
    long maxBefore = output.size() + cellCount;
    if (maxBefore > MAX_BLOCKS * 8L) throw new IllegalArgumentException("投影文件过大，无法安全展开区域 “" + label + "”。");
    for (int index = 0; index < (int) cellCount; index++) {
      int paletteIndex = unpack(packed, index, bits);
      if (paletteIndex < 0 || paletteIndex >= palette.size()) throw new IllegalArgumentException("区域 “" + label + "” 包含无效方块调色板索引。");
      int x = index % sizeX;
      int plane = index / sizeX;
      int z = plane % sizeZ;
      int y = plane / sizeZ;
      int worldX = originX + x;
      int worldY = originY + y;
      int worldZ = originZ + z;
      BlockState state = palette.get(paletteIndex);
      String key = key(worldX, worldY, worldZ);
      if (isAir(state.name)) {
        output.remove(key);
      } else {
        output.put(key, new Block(worldX, worldY, worldZ, state.name, state.properties));
        if (output.size() > MAX_BLOCKS) throw new IllegalArgumentException("投影文件超过 " + MAX_BLOCKS + " 个非空气方块；请拆分后再渲染。");
      }
    }
    readBlockEntities(region, originX, originY, originZ, sizeX, sizeY, sizeZ, output);
    readEntities(region, label, originX, originY, originZ, sizeX, sizeY, sizeZ, entities);
  }

  private static void readBlockEntities(Map<String, Object> region, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ, Map<String, Block> output) {
    ArrayList<Object> values = new ArrayList<Object>();
    values.addAll(list(region.get("TileEntities")));
    values.addAll(list(region.get("BlockEntities")));
    for (Object value : values) {
      Map<String, Object> data = map(value);
      int[] position = ints(data.get("Pos"));
      if (position.length < 3) position = ints(data);
      if (position.length < 3) continue;
      int[] absolute = normalizeBlockEntityPosition(position, originX, originY, originZ, sizeX, sizeY, sizeZ);
      Block current = output.get(key(absolute[0], absolute[1], absolute[2]));
      if (current == null) continue;
      String name = current.name == null ? "" : current.name;
      String entityId = string(data.get("id"));
      if (!name.endsWith("_sign") && !"minecraft:sign".equals(entityId) && !entityId.endsWith(":sign")) continue;
      LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>(current.properties);
      String text = signText(data);
      if (!text.isEmpty()) properties.put("lrs_sign_text", text);
      Map<String, Object> front = map(data.get("front_text"));
      String color = string(front.get("color"));
      if (!color.isEmpty()) properties.put("lrs_sign_color", color);
      Object glowing = front.get("has_glowing_text");
      if (glowing != null) properties.put("lrs_sign_glowing", glowing);
      output.put(current.key(), new Block(current.x, current.y, current.z, current.name, properties));
    }
  }

  private static int[] normalizeBlockEntityPosition(int[] position, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
    boolean local = position[0] >= 0 && position[0] < sizeX && position[1] >= 0 && position[1] < sizeY && position[2] >= 0 && position[2] < sizeZ;
    if (local) return new int[] { originX + position[0], originY + position[1], originZ + position[2] };
    return position;
  }

  private static String signText(Map<String, Object> data) {
    ArrayList<String> lines = new ArrayList<String>();
    Map<String, Object> front = map(data.get("front_text"));
    for (Object message : list(front.get("messages"))) lines.add(componentText(message));
    if (lines.isEmpty()) {
      for (int index = 1; index <= 4; index++) {
        String value = string(data.get("Text" + index));
        if (!value.isEmpty()) lines.add(componentText(value));
      }
    }
    while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) lines.remove(lines.size() - 1);
    StringBuilder result = new StringBuilder();
    for (String line : lines) {
      if (result.length() > 0) result.append('\n');
      result.append(line == null ? "" : line.replace('\r', ' ').replace('\n', ' '));
    }
    return result.toString();
  }

  @SuppressWarnings("unchecked")
  private static String componentText(Object value) {
    return componentText(value, 0);
  }

  @SuppressWarnings("unchecked")
  private static String componentText(Object value, int depth) {
    if (value == null) return "";
    if (depth > 32) return String.valueOf(value);
    if (value instanceof Map) {
      Map<String, Object> component = (Map<String, Object>) value;
      StringBuilder result = new StringBuilder(string(component.get("text")));
      for (Object extra : list(component.get("extra"))) result.append(componentText(extra, depth + 1));
      return result.toString();
    }
    if (value instanceof List) {
      StringBuilder result = new StringBuilder();
      for (Object item : (List<Object>) value) result.append(componentText(item, depth + 1));
      return result.toString();
    }
    String raw = String.valueOf(value);
    if (!(value instanceof String)) return raw;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return raw;
    boolean structured = (trimmed.startsWith("{") && trimmed.endsWith("}"))
      || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    boolean quoted = trimmed.startsWith("\"") && trimmed.endsWith("\"");
    if (!structured && !quoted) return raw;
    try {
      Object parsed = WorkerJson.parse(trimmed);
      if (parsed instanceof Map || parsed instanceof List) return componentText(parsed, depth + 1);
      return parsed == null ? "" : String.valueOf(parsed);
    } catch (Exception ignored) {
      return raw;
    }
  }

  private static void readEntities(Map<String, Object> region, String label, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ, List<RawEntity> output) {
    for (Object value : list(region.get("Entities"))) {
      Map<String, Object> data = map(value);
      String name = string(data.get("id"));
      if (name.isEmpty()) name = string(data.get("Id"));
      if (name.isEmpty()) continue;
      String localName = name.indexOf(':') >= 0 ? name.substring(name.lastIndexOf(':') + 1) : name;
      if ("item".equals(localName)) continue;
      double[] position = decimals(data.get("Pos"));
      if (position.length < 3) position = decimals(data.get("Position"));
      if (position.length < 3) continue;
      double[] rotation = decimals(data.get("Rotation"));
      float yaw = rotation.length == 0 ? 0f : (float) rotation[0];
      output.add(new RawEntity(label, name, new LinkedHashMap<String, Object>(data), position.clone(), yaw,
        originX, originY, originZ, sizeX, sizeY, sizeZ));
    }
  }

  static double[] normalizeEntityPosition(double[] position, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
    if (position == null || position.length < 3) return new double[] { originX, originY, originZ };
    return new double[] { position[0] + originX, position[1] + originY, position[2] + originZ };
  }

  private static ArrayList<Entity> resolveEntities(List<RawEntity> rawEntities, Map<String, Block> occupied, Map<String, Object> metadata) {
    ArrayList<Entity> result = new ArrayList<Entity>(rawEntities.size());
    EntityFrame frame = inferEntityFrame(rawEntities, occupied, metadata);
    EntityDiagnostics.begin(rawEntities.size(), occupied.size());
    EntityDiagnostics.frame(frame, metadata);
    for (RawEntity raw : rawEntities) {
      EntityPlacement placement = resolveEntityPosition(raw, frame, occupied);
      Entity entity = new Entity(placement.value[0], placement.value[1], placement.value[2], raw.yaw, raw.name, raw.data,
        placement.mode, raw.position, placement.localScore, placement.rawScore, placement.tile, placement.globalOffset,
        placement.globalScore);
      result.add(entity);
      EntityDiagnostics.entity(raw, placement, entity);
    }
    EntityDiagnostics.finish(result.size());
    return result;
  }

  private static EntityPlacement resolveEntityPosition(RawEntity raw, EntityFrame frame, Map<String, Block> occupied) {
    double[] offset = frame.offset;
    String lower = raw.name == null ? "" : raw.name.toLowerCase(java.util.Locale.ROOT);
    int[] rawTile = tileCoordinates(raw.data);
    int[] tile = new int[0];
    double[] value = new double[] { raw.position[0] + offset[0], raw.position[1] + offset[1], raw.position[2] + offset[2] };
    String mode = "global-offset";
    if ((lower.contains("item_frame") || lower.contains("painting")) && rawTile.length >= 3) {
      int tileX = (int) Math.round(rawTile[0] + offset[0]);
      int tileY = (int) Math.round(rawTile[1] + offset[1]);
      int tileZ = (int) Math.round(rawTile[2] + offset[2]);
      tile = new int[] { tileX, tileY, tileZ };
      BlockFace facing = Entity.facingFromEntityData(raw.data, raw.yaw);
      if (facing == BlockFace.NORTH) value = new double[] { tileX + 0.5d, tileY + 0.5d, tileZ };
      else if (facing == BlockFace.SOUTH) value = new double[] { tileX + 0.5d, tileY + 0.5d, tileZ + 1d };
      else if (facing == BlockFace.WEST) value = new double[] { tileX, tileY + 0.5d, tileZ + 0.5d };
      else if (facing == BlockFace.EAST) value = new double[] { tileX + 1d, tileY + 0.5d, tileZ + 0.5d };
      else if (facing == BlockFace.UP) value = new double[] { tileX + 0.5d, tileY + 1d, tileZ + 0.5d };
      else value = new double[] { tileX + 0.5d, tileY, tileZ + 0.5d };
      mode += "|tile|facing=" + facing.id;
    }
    return new EntityPlacement(value, mode, frame.score, frame.score, tile, offset, frame.score);
  }

  private static EntityFrame inferEntityFrame(List<RawEntity> rawEntities, Map<String, Block> occupied, Map<String, Object> metadata) {
    int[] bounds = occupiedBounds(occupied);
    if (rawEntities.isEmpty()) return new EntityFrame(new double[] { 0d, 0d, 0d }, 0d, 1, bounds);
    ArrayList<Integer> xs = axisCandidates(rawEntities, bounds[0], bounds[3], 0);
    ArrayList<Integer> ys = axisCandidates(rawEntities, bounds[1], bounds[4], 1);
    ArrayList<Integer> zs = axisCandidates(rawEntities, bounds[2], bounds[5], 2);
    double bestScore = -Double.MAX_VALUE;
    int bestX = 0;
    int bestY = 0;
    int bestZ = 0;
    int candidates = 0;
    for (Integer x : xs) {
      for (Integer y : ys) {
        for (Integer z : zs) {
          candidates++;
          double score = frameScore(rawEntities, occupied, bounds, x.intValue(), y.intValue(), z.intValue());
          if (score > bestScore) {
            bestScore = score;
            bestX = x.intValue();
            bestY = y.intValue();
            bestZ = z.intValue();
          }
        }
      }
    }
    if (bestScore == -Double.MAX_VALUE) bestScore = 0d;
    return new EntityFrame(new double[] { bestX, bestY, bestZ }, bestScore, candidates, bounds);
  }

  private static ArrayList<Integer> axisCandidates(List<RawEntity> rawEntities, int min, int max, int axis) {
    final int margin = 2;
    int low = Integer.MIN_VALUE / 4;
    int high = Integer.MAX_VALUE / 4;
    for (RawEntity raw : rawEntities) {
      double value = raw.position[axis];
      low = Math.max(low, (int) Math.ceil(min - value - margin));
      high = Math.min(high, (int) Math.floor(max - value + margin));
    }
    if (low > high) {
      double average = 0d;
      for (RawEntity raw : rawEntities) average += raw.position[axis];
      average /= rawEntities.size();
      int center = (int) Math.round(((double) min + (double) max) * 0.5d - average);
      low = center - 32;
      high = center + 32;
    }
    if (high - low > 64) {
      int center = (low + high) / 2;
      low = center - 32;
      high = center + 32;
    }
    ArrayList<Integer> values = new ArrayList<Integer>(Math.max(4, high - low + 2));
    for (int value = low; value <= high; value++) values.add(Integer.valueOf(value));
    if (!values.contains(Integer.valueOf(0))) values.add(Integer.valueOf(0));
    return values;
  }

  private static int[] occupiedBounds(Map<String, Block> occupied) {
    if (occupied.isEmpty()) return new int[] { 0, 0, 0, 0, 0, 0 };
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (Block block : occupied.values()) {
      minX = Math.min(minX, block.x);
      minY = Math.min(minY, block.y);
      minZ = Math.min(minZ, block.z);
      maxX = Math.max(maxX, block.x);
      maxY = Math.max(maxY, block.y);
      maxZ = Math.max(maxZ, block.z);
    }
    return new int[] { minX, minY, minZ, maxX, maxY, maxZ };
  }

  private static double frameScore(List<RawEntity> entities, Map<String, Block> occupied, int[] bounds, int dx, int dy, int dz) {
    double score = 0d;
    for (RawEntity raw : entities) {
      double x = raw.position[0] + dx;
      double y = raw.position[1] + dy;
      double z = raw.position[2] + dz;
      double outside = outsideDistance(x, bounds[0] - 1d, bounds[3] + 1d)
        + outsideDistance(y, bounds[1] - 1d, bounds[4] + 1d)
        + outsideDistance(z, bounds[2] - 1d, bounds[5] + 1d);
      score -= outside * 1_000_000d;
      score += outside == 0d ? 50_000d : 0d;
      boolean regionLocal = raw.position[0] >= -1.5d && raw.position[0] <= raw.sizeX + 1.5d
        && raw.position[1] >= -1.5d && raw.position[1] <= raw.sizeY + 1.5d
        && raw.position[2] >= -1.5d && raw.position[2] <= raw.sizeZ + 1.5d;
      if (regionLocal && dx == raw.originX && dy == raw.originY && dz == raw.originZ) score += 300_000d;
      String lower = raw.name == null ? "" : raw.name.toLowerCase(java.util.Locale.ROOT);
      int[] tile = tileCoordinates(raw.data);
      if ((lower.contains("item_frame") || lower.contains("painting")) && tile.length >= 3) {
        int tx = (int) Math.round(tile[0] + dx);
        int ty = (int) Math.round(tile[1] + dy);
        int tz = (int) Math.round(tile[2] + dz);
        if (occupied.containsKey(key(tx, ty, tz))) score += 500_000d;
        else if (nearbyBlock(occupied, tx, ty, tz, 1, false)) score += 100_000d;
        else score -= 100_000d;
      } else if (lower.contains("minecart")) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        int railDistance = nearestDistance(occupied, cx, cy, cz, 2, true);
        if (railDistance <= 9) score += 250_000d - railDistance * 2_000d;
        else score -= 50_000d;
      } else if (nearbyBlock(occupied, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 2, false)) {
        score += 10_000d;
      }
    }
    return score;
  }

  private static boolean nearbyBlock(Map<String, Block> occupied, int x, int y, int z, int radius, boolean railOnly) {
    return nearestDistance(occupied, x, y, z, radius, railOnly) < Integer.MAX_VALUE;
  }

  private static int nearestDistance(Map<String, Block> occupied, int x, int y, int z, int radius, boolean railOnly) {
    int best = Integer.MAX_VALUE;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          Block block = occupied.get(key(x + dx, y + dy, z + dz));
          if (block == null) continue;
          if (railOnly && (block.name == null || !block.name.contains("rail"))) continue;
          best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
      }
    }
    return best;
  }

  private static double outsideDistance(double value, double min, double max) {
    if (value < min) return min - value;
    if (value > max) return value - max;
    return 0d;
  }

  private static int[] tileCoordinates(Map<String, Object> data) {
    Integer x = integer(data.get("TileX"));
    Integer y = integer(data.get("TileY"));
    Integer z = integer(data.get("TileZ"));
    if (x == null || y == null || z == null) return new int[0];
    return new int[] { x.intValue(), y.intValue(), z.intValue() };
  }

  private static int unpack(long[] packed, int index, int bits) {
    long bitIndex = (long) index * (long) bits;
    int longIndex = (int) (bitIndex >>> 6);
    int offset = (int) (bitIndex & 63L);
    long value = packed[longIndex] >>> offset;
    if (offset + bits > 64) value |= packed[longIndex + 1] << (64 - offset);
    long mask = (1L << bits) - 1L;
    return (int) (value & mask);
  }

  private static int bitsForPalette(int size) {
    int value = Math.max(1, size - 1);
    int bits = 0;
    while (value > 0) {
      bits++;
      value >>>= 1;
    }
    return bits;
  }

  private static boolean isAir(String name) {
    return "minecraft:air".equals(name) || "minecraft:cave_air".equals(name) || "minecraft:void_air".equals(name);
  }

  private static String key(int x, int y, int z) {
    return x + ":" + y + ":" + z;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
  }

  private static int[] ints(Object value) {
    if (value instanceof int[]) return (int[]) value;
    if (value instanceof List) {
      List<?> values = (List<?>) value;
      if (values.size() < 3) return new int[0];
      Integer x = integer(values.get(0));
      Integer y = integer(values.get(1));
      Integer z = integer(values.get(2));
      return x == null || y == null || z == null ? new int[0] : new int[] { x.intValue(), y.intValue(), z.intValue() };
    }
    Map<String, Object> coordinate = map(value);
    if (coordinate.isEmpty()) return new int[0];
    Integer x = coordinateValue(coordinate, "x", "X");
    Integer y = coordinateValue(coordinate, "y", "Y");
    Integer z = coordinateValue(coordinate, "z", "Z");
    return x == null || y == null || z == null ? new int[0] : new int[] { x.intValue(), y.intValue(), z.intValue() };
  }

  private static Integer coordinateValue(Map<String, Object> coordinate, String lower, String upper) {
    Integer value = integer(coordinate.get(lower));
    return value == null ? integer(coordinate.get(upper)) : value;
  }

  private static Integer integer(Object value) {
    if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
    try {
      return value == null ? null : Integer.valueOf(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static long[] longs(Object value) {
    return value instanceof long[] ? (long[]) value : new long[0];
  }

  private static double[] decimals(Object value) {
    if (!(value instanceof List)) return new double[0];
    List<?> values = (List<?>) value;
    if (values.size() < 3) return new double[0];
    Double x = decimal(values.get(0));
    Double y = decimal(values.get(1));
    Double z = decimal(values.get(2));
    return x == null || y == null || z == null ? new double[0] : new double[] { x.doubleValue(), y.doubleValue(), z.doubleValue() };
  }

  private static Double decimal(Object value) {
    if (value instanceof Number) return Double.valueOf(((Number) value).doubleValue());
    try {
      return value == null ? null : Double.valueOf(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String string(Object value) {
    return value instanceof String ? (String) value : "";
  }

  private static final class BlockState {
    final String name;
    final Map<String, Object> properties;

    BlockState(String name, Map<String, Object> properties) {
      this.name = name;
      this.properties = properties;
    }
  }
}

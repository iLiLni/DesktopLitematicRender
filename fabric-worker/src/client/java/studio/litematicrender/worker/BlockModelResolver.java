package studio.litematicrender.worker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BlockModelResolver {
  private static final Object MISSING = new Object();
  private final TextureResolver resources;
  private final Map<String, Object> jsonCache = new HashMap<String, Object>();
  private final Map<String, ModelData> modelCache = new HashMap<String, ModelData>();
  private final Map<String, MachineGeometry.Shape> shapeCache = new HashMap<String, MachineGeometry.Shape>();
  private final Set<String> unresolved = new HashSet<String>();
  private int resolvedStates;

  BlockModelResolver(TextureResolver resources) {
    this.resources = resources;
  }

  MachineGeometry.Shape resolve(LitematicModel.Block block) {
    String stateKey = stateKey(block);
    MachineGeometry.Shape cached = shapeCache.get(stateKey);
    if (cached != null) return cached;
    if (unresolved.contains(stateKey)) return null;
    try {
      String[] id = identifier(block.name, "minecraft");
      Map<String, Object> state = json("assets/" + id[0] + "/blockstates/" + id[1] + ".json");
      if (state.isEmpty()) return missing(stateKey);
      ArrayList<ModelRef> refs = selectModels(state, block.properties, id[0]);
      if (refs.isEmpty()) return missing(stateKey);
      ArrayList<MachineGeometry.Part> parts = new ArrayList<MachineGeometry.Part>();
      for (ModelRef ref : refs) appendModel(parts, ref);
      if (parts.isEmpty()) return missing(stateKey);
      boolean fullCube = parts.size() == 1 && parts.get(0).box.isFullBlock() && parts.get(0).faces == MachineGeometry.ALL_FACES;
      boolean occluding = fullCube && !transparent(id[1]);
      MachineGeometry.Shape result = MachineGeometry.modelShape(parts, fullCube, occluding);
      shapeCache.put(stateKey, result);
      resolvedStates++;
      return result;
    } catch (Exception ignored) {
      return missing(stateKey);
    }
  }

  private MachineGeometry.Shape missing(String key) {
    unresolved.add(key);
    return null;
  }

  String report() {
    return "版本模型=" + resolvedStates + " 类状态，内置回退=" + unresolved.size() + " 类状态";
  }

  private ArrayList<ModelRef> selectModels(Map<String, Object> state, Map<String, Object> properties, String namespace) {
    ArrayList<ModelRef> result = new ArrayList<ModelRef>();
    Map<String, Object> variants = WorkerJson.object(state.get("variants"));
    String selected = null;
    int selectedTerms = -1;
    for (String key : variants.keySet()) {
      if (!variantMatches(key, properties)) continue;
      int terms = key.isEmpty() ? 0 : key.split(",").length;
      if (terms > selectedTerms) {
        selected = key;
        selectedTerms = terms;
      }
    }
    if (selected != null) addApplication(result, variants.get(selected), namespace);
    for (Object raw : WorkerJson.array(state.get("multipart"))) {
      Map<String, Object> part = WorkerJson.object(raw);
      if (part.isEmpty() || !whenMatches(part.get("when"), properties)) continue;
      addApplication(result, part.get("apply"), namespace);
    }
    return result;
  }

  private void addApplication(List<ModelRef> output, Object raw, String namespace) {
    if (raw instanceof List) {
      List<Object> values = WorkerJson.array(raw);
      if (!values.isEmpty()) addApplication(output, values.get(0), namespace);
      return;
    }
    Map<String, Object> value = WorkerJson.object(raw);
    String model = WorkerJson.string(value.get("model"));
    if (model.isEmpty()) return;
    output.add(new ModelRef(qualified(model, namespace), quarter(value.get("x")), quarter(value.get("y")), Boolean.TRUE.equals(value.get("uvlock"))));
  }

  private void appendModel(List<MachineGeometry.Part> output, ModelRef ref) {
    ModelData model = model(ref.model, new HashSet<String>());
    if (model == null || model.elements.isEmpty()) return;
    for (Object raw : model.elements) {
      Map<String, Object> element = WorkerJson.object(raw);
      double[] from = vector(element.get("from"), new double[] { 0d, 0d, 0d });
      double[] to = vector(element.get("to"), new double[] { 16d, 16d, 16d });
      MachineGeometry.Box original = MachineGeometry.modelBox(from[0] / 16d, from[1] / 16d, from[2] / 16d, to[0] / 16d, to[1] / 16d, to[2] / 16d);
      ElementRotation elementRotation = ElementRotation.parse(element.get("rotation"));
      MachineGeometry.Box transformed = transformBox(original, elementRotation, ref);
      EnumMap<BlockFace, MachineGeometry.FaceSpec> faces = new EnumMap<BlockFace, MachineGeometry.FaceSpec>(BlockFace.class);
      for (Map.Entry<String, Object> entry : WorkerJson.object(element.get("faces")).entrySet()) {
        BlockFace sourceFace = BlockFace.fromProperty(entry.getKey(), null);
        if (sourceFace == null) continue;
        Map<String, Object> face = WorkerJson.object(entry.getValue());
        String texture = resolveTexture(model, WorkerJson.string(face.get("texture")));
        if (texture == null) continue;
        String resource = textureResource(texture, model.namespace);
        double[] rectangle = face.containsKey("uv") ? uvRectangle(face.get("uv")) : defaultUv(original, sourceFace);
        double[] sourceUv = uvCorners(rectangle, quarter(face.get("rotation")));
        BlockFace targetFace = transformFace(sourceFace, elementRotation, ref);
        if (targetFace == null) continue;
        double[] targetUv = ref.uvlock ? uvCorners(defaultUv(transformed, targetFace), 0)
          : reorderUv(original, transformed, sourceFace, targetFace, sourceUv, elementRotation, ref);
        int tint = face.get("tintindex") instanceof Number ? ((Number) face.get("tintindex")).intValue() : -1;
        faces.put(targetFace, MachineGeometry.modelFace(resource, targetUv, tint));
      }
      if (!faces.isEmpty()) output.add(MachineGeometry.modelPart(transformed, faces));
    }
  }

  private ModelData model(String id, Set<String> visiting) {
    ModelData cached = modelCache.get(id);
    if (cached != null) return cached;
    if (!visiting.add(id)) return null;
    String[] value = identifier(id, "minecraft");
    Map<String, Object> json = json("assets/" + value[0] + "/models/" + value[1] + ".json");
    if (json.isEmpty()) return null;
    LinkedHashMap<String, String> textures = new LinkedHashMap<String, String>();
    ArrayList<Object> elements = new ArrayList<Object>();
    String parentId = WorkerJson.string(json.get("parent"));
    if (!parentId.isEmpty() && !parentId.startsWith("builtin/")) {
      ModelData parent = model(qualified(parentId, value[0]), visiting);
      if (parent != null) {
        textures.putAll(parent.textures);
        elements.addAll(parent.elements);
      }
    }
    for (Map.Entry<String, Object> entry : WorkerJson.object(json.get("textures")).entrySet()) {
      if (entry.getValue() instanceof String) textures.put(entry.getKey(), (String) entry.getValue());
    }
    if (json.containsKey("elements")) {
      elements.clear();
      elements.addAll(WorkerJson.array(json.get("elements")));
    }
    ModelData result = new ModelData(value[0], textures, elements);
    modelCache.put(id, result);
    visiting.remove(id);
    return result;
  }

  private String resolveTexture(ModelData model, String raw) {
    if (raw == null || raw.isEmpty()) return null;
    String value = raw;
    HashSet<String> seen = new HashSet<String>();
    while (value.startsWith("#")) {
      String key = value.substring(1);
      if (!seen.add(key)) return null;
      value = model.textures.get(key);
      if (value == null || value.isEmpty()) return null;
    }
    return value;
  }

  private Map<String, Object> json(String resource) {
    Object cached = jsonCache.get(resource);
    if (cached == MISSING) return new LinkedHashMap<String, Object>();
    if (cached instanceof Map) return WorkerJson.object(cached);
    String text = resources.text(resource);
    if (text == null || text.trim().isEmpty()) {
      jsonCache.put(resource, MISSING);
      return new LinkedHashMap<String, Object>();
    }
    Object parsed = WorkerJson.parse(text);
    if (!(parsed instanceof Map)) {
      jsonCache.put(resource, MISSING);
      return new LinkedHashMap<String, Object>();
    }
    jsonCache.put(resource, parsed);
    return WorkerJson.object(parsed);
  }

  private boolean variantMatches(String key, Map<String, Object> properties) {
    if (key == null || key.isEmpty()) return true;
    for (String term : key.split(",")) {
      int equals = term.indexOf('=');
      if (equals <= 0) return false;
      String actual = property(properties, term.substring(0, equals));
      if (!oneOf(actual, term.substring(equals + 1))) return false;
    }
    return true;
  }

  private boolean whenMatches(Object raw, Map<String, Object> properties) {
    if (raw == null) return true;
    Map<String, Object> when = WorkerJson.object(raw);
    if (when.isEmpty()) return true;
    if (when.containsKey("OR")) {
      for (Object option : WorkerJson.array(when.get("OR"))) if (whenMatches(option, properties)) return true;
      return false;
    }
    if (when.containsKey("AND")) {
      for (Object option : WorkerJson.array(when.get("AND"))) if (!whenMatches(option, properties)) return false;
      return true;
    }
    for (Map.Entry<String, Object> entry : when.entrySet()) {
      if (!oneOf(property(properties, entry.getKey()), String.valueOf(entry.getValue()))) return false;
    }
    return true;
  }

  private boolean oneOf(String actual, String expected) {
    for (String option : expected.split("\\|")) if (actual.equals(option.toLowerCase(Locale.ROOT))) return true;
    return false;
  }

  private MachineGeometry.Box transformBox(MachineGeometry.Box box, ElementRotation element, ModelRef ref) {
    Vec[] corners = boxCorners(box);
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
    for (Vec raw : corners) {
      Vec value = transform(raw, element, ref);
      minX = Math.min(minX, value.x); minY = Math.min(minY, value.y); minZ = Math.min(minZ, value.z);
      maxX = Math.max(maxX, value.x); maxY = Math.max(maxY, value.y); maxZ = Math.max(maxZ, value.z);
    }
    return MachineGeometry.modelBox(snap(minX), snap(minY), snap(minZ), snap(maxX), snap(maxY), snap(maxZ));
  }

  private BlockFace transformFace(BlockFace face, ElementRotation element, ModelRef ref) {
    Vec normal = new Vec(face.dx, face.dy, face.dz);
    if (element.active && element.quarter != 0) normal = rotate(normal, element.axis, element.quarter);
    normal = rotateX(normal, ref.x);
    normal = rotateY(normal, ref.y);
    int x = (int) Math.round(normal.x), y = (int) Math.round(normal.y), z = (int) Math.round(normal.z);
    for (BlockFace candidate : BlockFace.values()) if (candidate.dx == x && candidate.dy == y && candidate.dz == z) return candidate;
    return null;
  }

  private double[] reorderUv(MachineGeometry.Box original, MachineGeometry.Box transformed, BlockFace sourceFace, BlockFace targetFace,
                             double[] sourceUv, ElementRotation element, ModelRef ref) {
    Vec[] source = faceCorners(original, sourceFace);
    Vec[] target = faceCorners(transformed, targetFace);
    double[] result = new double[8];
    boolean[] assigned = new boolean[4];
    for (int index = 0; index < 4; index++) {
      Vec moved = transform(source[index], element, ref);
      int nearest = nearest(moved, target);
      result[nearest * 2] = sourceUv[index * 2];
      result[nearest * 2 + 1] = sourceUv[index * 2 + 1];
      assigned[nearest] = true;
    }
    for (boolean value : assigned) if (!value) return sourceUv;
    return result;
  }

  private Vec transform(Vec value, ElementRotation element, ModelRef ref) {
    Vec result = value;
    if (element.active && element.quarter != 0) {
      result = new Vec(result.x - element.origin.x, result.y - element.origin.y, result.z - element.origin.z);
      result = rotate(result, element.axis, element.quarter);
      result = new Vec(result.x + element.origin.x, result.y + element.origin.y, result.z + element.origin.z);
    }
    result = new Vec(result.x - 0.5d, result.y - 0.5d, result.z - 0.5d);
    result = rotateX(result, ref.x);
    result = rotateY(result, ref.y);
    return new Vec(result.x + 0.5d, result.y + 0.5d, result.z + 0.5d);
  }

  private Vec rotate(Vec value, char axis, int quarter) {
    if (axis == 'x') return rotateX(value, quarter);
    if (axis == 'z') return rotateZ(value, quarter);
    return rotateY(value, quarter);
  }

  private Vec rotateX(Vec value, int raw) {
    int turns = Math.floorMod(raw, 4);
    Vec result = value;
    for (int index = 0; index < turns; index++) result = new Vec(result.x, -result.z, result.y);
    return result;
  }

  private Vec rotateY(Vec value, int raw) {
    int turns = Math.floorMod(raw, 4);
    Vec result = value;
    for (int index = 0; index < turns; index++) result = new Vec(-result.z, result.y, result.x);
    return result;
  }

  private Vec rotateZ(Vec value, int raw) {
    int turns = Math.floorMod(raw, 4);
    Vec result = value;
    for (int index = 0; index < turns; index++) result = new Vec(-result.y, result.x, result.z);
    return result;
  }

  private Vec[] boxCorners(MachineGeometry.Box box) {
    return new Vec[] {
      new Vec(box.minX, box.minY, box.minZ), new Vec(box.maxX, box.minY, box.minZ),
      new Vec(box.minX, box.maxY, box.minZ), new Vec(box.maxX, box.maxY, box.minZ),
      new Vec(box.minX, box.minY, box.maxZ), new Vec(box.maxX, box.minY, box.maxZ),
      new Vec(box.minX, box.maxY, box.maxZ), new Vec(box.maxX, box.maxY, box.maxZ)
    };
  }

  private Vec[] faceCorners(MachineGeometry.Box box, BlockFace face) {
    if (face == BlockFace.EAST) return new Vec[] { new Vec(box.maxX, box.maxY, box.minZ), new Vec(box.maxX, box.maxY, box.maxZ), new Vec(box.maxX, box.minY, box.maxZ), new Vec(box.maxX, box.minY, box.minZ) };
    if (face == BlockFace.WEST) return new Vec[] { new Vec(box.minX, box.maxY, box.maxZ), new Vec(box.minX, box.maxY, box.minZ), new Vec(box.minX, box.minY, box.minZ), new Vec(box.minX, box.minY, box.maxZ) };
    if (face == BlockFace.SOUTH) return new Vec[] { new Vec(box.maxX, box.maxY, box.maxZ), new Vec(box.minX, box.maxY, box.maxZ), new Vec(box.minX, box.minY, box.maxZ), new Vec(box.maxX, box.minY, box.maxZ) };
    if (face == BlockFace.NORTH) return new Vec[] { new Vec(box.minX, box.maxY, box.minZ), new Vec(box.maxX, box.maxY, box.minZ), new Vec(box.maxX, box.minY, box.minZ), new Vec(box.minX, box.minY, box.minZ) };
    if (face == BlockFace.DOWN) return new Vec[] { new Vec(box.minX, box.minY, box.maxZ), new Vec(box.maxX, box.minY, box.maxZ), new Vec(box.maxX, box.minY, box.minZ), new Vec(box.minX, box.minY, box.minZ) };
    return new Vec[] { new Vec(box.minX, box.maxY, box.minZ), new Vec(box.maxX, box.maxY, box.minZ), new Vec(box.maxX, box.maxY, box.maxZ), new Vec(box.minX, box.maxY, box.maxZ) };
  }

  private double[] defaultUv(MachineGeometry.Box box, BlockFace face) {
    if (face == BlockFace.UP) return rect(box.minX, box.minZ, box.maxX, box.maxZ);
    if (face == BlockFace.DOWN) return rect(box.minX, 1d - box.maxZ, box.maxX, 1d - box.minZ);
    if (face == BlockFace.EAST) return rect(box.minZ, 1d - box.maxY, box.maxZ, 1d - box.minY);
    if (face == BlockFace.WEST) return rect(1d - box.maxZ, 1d - box.maxY, 1d - box.minZ, 1d - box.minY);
    if (face == BlockFace.SOUTH) return rect(1d - box.maxX, 1d - box.maxY, 1d - box.minX, 1d - box.minY);
    return rect(box.minX, 1d - box.maxY, box.maxX, 1d - box.minY);
  }

  private double[] rect(double u1, double v1, double u2, double v2) {
    return new double[] { u1, v1, u2, v2 };
  }

  private double[] uvRectangle(Object raw) {
    List<Object> value = WorkerJson.array(raw);
    if (value.size() < 4) return new double[] { 0d, 0d, 1d, 1d };
    return new double[] {
      number(value.get(0), 0d) / 16d, number(value.get(1), 0d) / 16d,
      number(value.get(2), 16d) / 16d, number(value.get(3), 16d) / 16d
    };
  }

  private double[] uvCorners(double[] rect, int rotation) {
    double[][] target = new double[][] { { 0d, 0d }, { 1d, 0d }, { 1d, 1d }, { 0d, 1d } };
    double[] result = new double[8];
    for (int index = 0; index < 4; index++) {
      double u = target[index][0], v = target[index][1];
      int turns = Math.floorMod(rotation, 4);
      if (turns == 1) { double old = u; u = v; v = 1d - old; }
      else if (turns == 2) { u = 1d - u; v = 1d - v; }
      else if (turns == 3) { double old = u; u = 1d - v; v = old; }
      result[index * 2] = rect[0] + (rect[2] - rect[0]) * u;
      result[index * 2 + 1] = rect[1] + (rect[3] - rect[1]) * v;
    }
    return result;
  }

  private int nearest(Vec value, Vec[] candidates) {
    int best = 0;
    double distance = Double.POSITIVE_INFINITY;
    for (int index = 0; index < candidates.length; index++) {
      double dx = value.x - candidates[index].x, dy = value.y - candidates[index].y, dz = value.z - candidates[index].z;
      double next = dx * dx + dy * dy + dz * dz;
      if (next < distance) { distance = next; best = index; }
    }
    return best;
  }

  private double[] vector(Object raw, double[] fallback) {
    List<Object> values = WorkerJson.array(raw);
    if (values.size() < 3) return fallback;
    return new double[] { number(values.get(0), fallback[0]), number(values.get(1), fallback[1]), number(values.get(2), fallback[2]) };
  }

  private double number(Object raw, double fallback) {
    return raw instanceof Number ? ((Number) raw).doubleValue() : fallback;
  }

  private int quarter(Object raw) {
    if (!(raw instanceof Number)) return 0;
    return Math.floorMod((int) Math.round(((Number) raw).doubleValue() / 90d), 4);
  }

  private double snap(double value) {
    double rounded = Math.rint(value * 1_000_000d) / 1_000_000d;
    return Math.abs(rounded) < 0.000001d ? 0d : Math.abs(rounded - 1d) < 0.000001d ? 1d : rounded;
  }

  private String textureResource(String id, String namespace) {
    String[] value = identifier(id, namespace);
    return "assets/" + value[0] + "/textures/" + value[1] + ".png";
  }

  private String qualified(String id, String namespace) {
    return id.indexOf(':') >= 0 ? id : namespace + ":" + id;
  }

  private String[] identifier(String raw, String fallbackNamespace) {
    String id = raw == null ? "" : raw;
    int colon = id.indexOf(':');
    String namespace = colon >= 0 ? id.substring(0, colon) : fallbackNamespace;
    String path = colon >= 0 ? id.substring(colon + 1) : id;
    return new String[] { namespace.isEmpty() ? "minecraft" : namespace, path };
  }

  private String property(Map<String, Object> properties, String key) {
    Object value = properties.get(key);
    return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
  }

  private String stateKey(LitematicModel.Block block) {
    StringBuilder result = new StringBuilder(block.name == null ? "minecraft:stone" : block.name);
    ArrayList<String> keys = new ArrayList<String>(block.properties.keySet());
    java.util.Collections.sort(keys);
    for (String key : keys) result.append('|').append(key).append('=').append(block.properties.get(key));
    return result.toString();
  }

  private boolean transparent(String name) {
    return name.contains("glass") || name.contains("ice") || name.contains("leaves") || name.contains("slime") || name.contains("honey")
      || name.contains("pane") || name.contains("barrier");
  }

  private static final class ModelRef {
    final String model;
    final int x;
    final int y;
    final boolean uvlock;

    ModelRef(String model, int x, int y, boolean uvlock) {
      this.model = model;
      this.x = x;
      this.y = y;
      this.uvlock = uvlock;
    }
  }

  private static final class ModelData {
    final String namespace;
    final Map<String, String> textures;
    final List<Object> elements;

    ModelData(String namespace, Map<String, String> textures, List<Object> elements) {
      this.namespace = namespace;
      this.textures = textures;
      this.elements = elements;
    }
  }

  private static final class ElementRotation {
    final boolean active;
    final Vec origin;
    final char axis;
    final int quarter;

    ElementRotation(boolean active, Vec origin, char axis, int quarter) {
      this.active = active;
      this.origin = origin;
      this.axis = axis;
      this.quarter = quarter;
    }

    static ElementRotation parse(Object raw) {
      Map<String, Object> value = WorkerJson.object(raw);
      double angle = value.get("angle") instanceof Number ? ((Number) value.get("angle")).doubleValue() : 0d;
      double rounded = Math.rint(angle / 90d);
      if (Math.abs(angle - rounded * 90d) > 0.001d) return new ElementRotation(false, new Vec(0.5d, 0.5d, 0.5d), 'y', 0);
      List<Object> origin = WorkerJson.array(value.get("origin"));
      Vec pivot = origin.size() >= 3
        ? new Vec(numberStatic(origin.get(0), 8d) / 16d, numberStatic(origin.get(1), 8d) / 16d, numberStatic(origin.get(2), 8d) / 16d)
        : new Vec(0.5d, 0.5d, 0.5d);
      String axis = WorkerJson.string(value.get("axis"));
      return new ElementRotation(angle != 0d, pivot, axis.isEmpty() ? 'y' : axis.charAt(0), (int) rounded);
    }

    private static double numberStatic(Object raw, double fallback) {
      return raw instanceof Number ? ((Number) raw).doubleValue() : fallback;
    }
  }

  private static final class Vec {
    final double x;
    final double y;
    final double z;

    Vec(double x, double y, double z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }
}

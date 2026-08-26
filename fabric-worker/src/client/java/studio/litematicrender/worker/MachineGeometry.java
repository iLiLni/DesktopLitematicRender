package studio.litematicrender.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MachineGeometry {
  static final String BLOCK = "block";
  static final String REDSTONE_TOP = "redstone_top";
  static final String REDSTONE_VERTICAL = "redstone_vertical";
  static final String REPEATER_BASE = "repeater_base";
  static final String COMPARATOR_BASE = "comparator_base";
  static final String TORCH_ON = "torch_on";
  static final String TORCH_OFF = "torch_off";
  static final String REPEATER_LOCK = "repeater_lock";
  static final String PISTON_BODY = "piston_body";
  static final String PISTON_HEAD = "piston_head";
  static final String PISTON_ROD = "piston_rod";
  static final String HOPPER_OUTSIDE = "hopper_outside";
  static final String HOPPER_INSIDE = "hopper_inside";
  static final String RAIL_FLAT = "rail_flat";
  static final String RAIL_CURVED = "rail_curved";
  static final String RAIL_ASCENDING = "rail_ascending";
  static final String FLUID_WATER = "fluid_water";
  static final String FLUID_WATER_FLOW = "fluid_water_flow";
  static final String FLUID_LAVA = "fluid_lava";
  static final String FLUID_LAVA_FLOW = "fluid_lava_flow";
  static final String TORCH_STEM = "torch_stem";
  static final String TORCH_HEAD_ON = "torch_head_on";
  static final String TORCH_HEAD_OFF = "torch_head_off";
  static final String CHEST_BOTTOM = "chest_bottom";
  static final String CHEST_LID = "chest_lid";
  static final String CHEST_DOUBLE_BOTTOM = "chest_double_bottom";
  static final String CHEST_DOUBLE_LID = "chest_double_lid";
  static final String CHEST_LATCH = "chest_latch";
  static final String MINECART = "minecart";
  static final String MINECART_WHEEL = "minecart_wheel";
  static final String MINECART_CARGO = "minecart_cargo";
  static final String ENTITY = "entity";
  static final String ITEM_ENTITY = "item_entity";
  static final String ITEM_FRAME = "item_frame";
  static final String FRAME_ITEM = "frame_item";
  static final String SIGN_BOARD = "sign_board";
  static final String SIGN_POST = "sign_post";
  static final String SIGN_TEXT = "sign_text";
  static final int ALL_FACES = (1 << BlockFace.values().length) - 1;
  static final int TOP_FACE = mask(BlockFace.UP);
  static final int SIDE_FACES = mask(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

  static final class FaceSpec {
    final String resource;
    final double[] uv;
    final int tintIndex;

    FaceSpec(String resource, double[] uv, int tintIndex) {
      this.resource = resource;
      this.uv = uv == null ? null : uv.clone();
      this.tintIndex = tintIndex;
    }
  }

  static final class Box {
    final double minX;
    final double minY;
    final double minZ;
    final double maxX;
    final double maxY;
    final double maxZ;

    Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
      this.minX = minX;
      this.minY = minY;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxY = maxY;
      this.maxZ = maxZ;
    }

    boolean isFullBlock() {
      return minX == 0d && minY == 0d && minZ == 0d && maxX == 1d && maxY == 1d && maxZ == 1d;
    }

    double centerX() { return (minX + maxX) * 0.5d; }
    double centerY() { return (minY + maxY) * 0.5d; }
    double centerZ() { return (minZ + maxZ) * 0.5d; }
  }

  static final class Part {
    final Box box;
    final String material;
    final int faces;
    final boolean outline;
    final boolean cropTexture;
    final BlockFace slopeUp;
    final Map<BlockFace, FaceSpec> faceSpecs;
    final double[] topHeights;
    final double textureAngleDeg;

    Part(Box box, String material, int faces, boolean outline, boolean cropTexture) {
      this(box, material, faces, outline, cropTexture, null);
    }

    Part(Box box, String material, int faces, boolean outline, boolean cropTexture, BlockFace slopeUp) {
      this(box, material, faces, outline, cropTexture, slopeUp, null, null, 0);
    }

    Part(Box box, String material, int faces, boolean outline, boolean cropTexture, BlockFace slopeUp,
         Map<BlockFace, FaceSpec> faceSpecs, double[] topHeights, double textureAngleDeg) {
      this.box = box;
      this.material = material;
      this.faces = faces;
      this.outline = outline;
      this.cropTexture = cropTexture;
      this.slopeUp = slopeUp;
      EnumMap<BlockFace, FaceSpec> values = new EnumMap<BlockFace, FaceSpec>(BlockFace.class);
      if (faceSpecs != null) values.putAll(faceSpecs);
      this.faceSpecs = Collections.unmodifiableMap(values);
      this.topHeights = topHeights == null ? null : topHeights.clone();
      this.textureAngleDeg = textureAngleDeg;
    }

    boolean shows(BlockFace face) {
      return (faces & (1 << face.ordinal())) != 0;
    }

    FaceSpec faceSpec(BlockFace face) {
      return faceSpecs.get(face);
    }
  }

  static final class Shape {
    final List<Part> parts;
    final List<Box> boxes;
    final boolean fullCube;
    final boolean occluding;

    Shape(List<Part> parts, boolean fullCube, boolean occluding) {
      this.parts = Collections.unmodifiableList(parts);
      ArrayList<Box> values = new ArrayList<Box>(parts.size());
      for (Part part : parts) values.add(part.box);
      this.boxes = Collections.unmodifiableList(values);
      this.fullCube = fullCube;
      this.occluding = occluding;
    }
  }

  private static final Shape FULL = shape(true, true, part(box(0d, 0d, 0d, 1d, 1d, 1d)));
  private static final Shape TRANSPARENT_FULL = shape(true, false, part(box(0d, 0d, 0d, 1d, 1d, 1d)));

  private MachineGeometry() {
  }

  static Shape shape(LitematicModel.Block block) {
    return shape(null, block);
  }

  static Shape shape(LitematicModel model, LitematicModel.Block block) {
    String name = localName(block.name);
    Map<String, Object> properties = block.properties;
    if (truth(properties, "lrs_entity")) return name.contains("minecart") ? minecart(name, properties) : entity(name, properties);
    Shape result;
    if ("water".equals(name) || "lava".equals(name)) result = fluid(model, block, name, properties);
    else if (name.endsWith("_carpet") || "moss_carpet".equals(name)) result = carpet();
    else if (name.endsWith("_wall_banner") || name.endsWith("_banner")) result = banner(name, properties);
    else if (isSign(name)) result = sign(name, properties);
    else if (isTransparent(name)) result = TRANSPARENT_FULL;
    else if (name.endsWith("_slab")) result = slab(properties);
    else if (name.endsWith("_stairs")) result = stairs(properties);
    else if ("redstone_wire".equals(name)) result = redstoneWire(properties);
    else if ("tripwire".equals(name)) result = shape(false, false, part(box(0d, 0.015d, 0d, 1d, 0.022d, 1d), BLOCK, TOP_FACE, false));
    else if (name.endsWith("_rail") || "rail".equals(name)) result = rail(properties);
    else if ("repeater".equals(name)) result = repeater(properties);
    else if ("comparator".equals(name)) result = comparator(properties);
    else if ("piston".equals(name) || "sticky_piston".equals(name)) result = piston(name, properties);
    else if ("piston_head".equals(name) || "moving_piston".equals(name)) result = pistonHead(properties);
    else if (name.endsWith("_torch") || "torch".equals(name)) result = torch(block);
    else if (name.endsWith("_button") || "stone_button".equals(name)) result = button(properties);
    else if ("lever".equals(name)) result = lever(properties);
    else if (name.endsWith("_pressure_plate")) result = pressurePlate(properties);
    else if (name.endsWith("_trapdoor")) result = trapdoor(properties);
    else if (name.endsWith("_fence")) result = fence(properties);
    else if (name.endsWith("_wall")) result = wall(properties);
    else if (name.endsWith("_pane") || "iron_bars".equals(name)) result = panes(properties);
    else if ("hopper".equals(name)) result = hopper(properties);
    else if (name.endsWith("chest")) result = chest(model, block);
    else if (name.contains("minecart")) result = minecart(name, properties);
    else result = FULL;
    return truth(properties, "waterlogged") && !result.fullCube ? waterlogged(result) : result;
  }

  static boolean occludes(LitematicModel.Block block) {
    if (block == null) return false;
    Shape shape = shape(block);
    return shape.fullCube && shape.occluding;
  }

  private static Shape redstoneWire(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0d, 0.015d, 0d, 1d, 0.022d, 1d), REDSTONE_TOP, TOP_FACE, false));
    if ("up".equals(property(properties, "north"))) parts.add(redstoneClimb(BlockFace.NORTH));
    if ("up".equals(property(properties, "south"))) parts.add(redstoneClimb(BlockFace.SOUTH));
    if ("up".equals(property(properties, "east"))) parts.add(redstoneClimb(BlockFace.EAST));
    if ("up".equals(property(properties, "west"))) parts.add(redstoneClimb(BlockFace.WEST));
    return new Shape(parts, false, false);
  }

  private static Part redstoneClimb(BlockFace direction) {
    if (direction == BlockFace.NORTH) return new Part(box(0.375d, 0.018d, 0.001d, 0.625d, 1.002d, 0.008d), REDSTONE_VERTICAL, mask(BlockFace.SOUTH), false, false, null, null, null, 0d);
    if (direction == BlockFace.SOUTH) return new Part(box(0.375d, 0.018d, 0.992d, 0.625d, 1.002d, 0.999d), REDSTONE_VERTICAL, mask(BlockFace.NORTH), false, false, null, null, null, 0d);
    if (direction == BlockFace.EAST) return new Part(box(0.992d, 0.018d, 0.375d, 0.999d, 1.002d, 0.625d), REDSTONE_VERTICAL, mask(BlockFace.WEST), false, false, null, null, null, 0d);
    return new Part(box(0.001d, 0.018d, 0.375d, 0.008d, 1.002d, 0.625d), REDSTONE_VERTICAL, mask(BlockFace.EAST), false, false, null, null, null, 0d);
  }

  private static Shape rail(Map<String, Object> properties) {
    String state = property(properties, "shape");
    if (state.startsWith("ascending_")) {
      BlockFace high = BlockFace.fromProperty(state.substring("ascending_".length()), BlockFace.NORTH);
      return shape(false, false, part(box(0d, 0.015d, 0d, 1d, 1.015d, 1d), RAIL_ASCENDING, TOP_FACE, false, false, high));
    }
    if (state.contains("north_east") || state.contains("north_west") || state.contains("south_east") || state.contains("south_west")) {
      return shape(false, false, curvedRail(state));
    }
    return shape(false, false, part(box(0d, 0.015d, 0d, 1d, 0.032d, 1d), RAIL_FLAT, TOP_FACE, false));
  }

  private static Part curvedRail(String state) {
    EnumMap<BlockFace, FaceSpec> faces = new EnumMap<BlockFace, FaceSpec>(BlockFace.class);
    faces.put(BlockFace.UP, new FaceSpec("assets/minecraft/textures/block/rail_corner.png", quarterUv(curveQuarter(state)), -1));
    return new Part(box(0d, 0.015d, 0d, 1d, 0.032d, 1d), RAIL_CURVED, TOP_FACE, false, false, null, faces, null, 0d);
  }

  private static int curveQuarter(String state) {
    if ("north_east".equals(state)) return 3;
    if ("south_east".equals(state)) return 0;
    if ("south_west".equals(state)) return 1;
    return 2;
  }

  private static Shape fluid(LitematicModel model, LitematicModel.Block block, String name, Map<String, Object> properties) {
    double[] heights = fluidCorners(model, block, name);
    double maximum = Math.max(Math.max(heights[0], heights[1]), Math.max(heights[2], heights[3]));
    boolean flowing = number(properties, "level", 0) != 0 || spread(heights) > 0.015d;
    double rotation = fluidRotation(model, block, name);
    String material = "water".equals(name)
      ? (flowing ? FLUID_WATER_FLOW : FLUID_WATER)
      : (flowing ? FLUID_LAVA_FLOW : FLUID_LAVA);
    Part surface = new Part(box(0d, 0d, 0d, 1d, maximum, 1d), material, ALL_FACES, false, false, null, null, heights, rotation);
    return shape(false, false, surface);
  }

  private static Shape waterlogged(Shape base) {
    ArrayList<Part> parts = new ArrayList<Part>(base.parts);
    parts.add(new Part(box(0d, 0d, 0d, 1d, 0.875d, 1d), FLUID_WATER, ALL_FACES, false, false, null, null,
      new double[] { 0.875d, 0.875d, 0.875d, 0.875d }, 0));
    return new Shape(parts, false, false);
  }

  static Shape withWaterlogged(Shape base) {
    return waterlogged(base);
  }

  private static Shape repeater(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0d, 0d, 0d, 1d, 0.125d, 1d), REPEATER_BASE, ALL_FACES, true));
    boolean powered = truth(properties, "powered");
    int delay = clamp(number(properties, "delay", 1), 1, 4);
    torchParts(parts, 0.5d, 0.25d, 0.125d, 0.55d, powered);
    torchParts(parts, 0.5d, 0.50d + (delay - 1) * 0.125d, 0.125d, 0.55d, powered);
    if (truth(properties, "locked")) parts.add(part(box(0.125d, 0.16d, 0.55d, 0.875d, 0.25d, 0.66d), REPEATER_LOCK, ALL_FACES, false));
    return rotateHorizontal(parts, BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH));
  }

  private static Shape comparator(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0d, 0d, 0d, 1d, 0.125d, 1d), COMPARATOR_BASE, ALL_FACES, true));
    boolean powered = truth(properties, "powered");
    torchParts(parts, 0.25d, 0.72d, 0.125d, 0.50d, powered);
    torchParts(parts, 0.75d, 0.72d, 0.125d, 0.50d, powered);
    boolean subtract = "subtract".equals(property(properties, "mode"));
    double outputZ = subtract ? 0.47d : 0.28d;
    double outputTop = subtract ? 0.70d : 0.54d;
    torchParts(parts, 0.5d, outputZ, 0.125d, outputTop, powered);
    return rotateHorizontal(parts, BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH));
  }

  private static Shape piston(String name, Map<String, Object> properties) {
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
    boolean sticky = "sticky_piston".equals(name);
    if (!truth(properties, "extended")) {
      return shape(true, true, pistonPart(box(0d, 0d, 0d, 1d, 1d, 1d), PISTON_BODY, facing, sticky, "base", true));
    }
    return pistonBase(facing, sticky);
  }

  private static Shape pistonBase(Map<String, Object> properties) {
    return pistonBase(BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH), false);
  }

  private static Shape pistonBase(BlockFace facing, boolean sticky) {
    ArrayList<Part> parts = new ArrayList<Part>();
    switch (facing) {
      case NORTH:
        parts.add(pistonPart(box(0d, 0d, 0.25d, 1d, 1d, 1d), PISTON_BODY, facing, sticky, "extended_base", true));
        parts.add(pistonPart(box(0.375d, 0.375d, 0d, 0.625d, 0.625d, 0.25d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case SOUTH:
        parts.add(pistonPart(box(0d, 0d, 0d, 1d, 1d, 0.75d), PISTON_BODY, facing, sticky, "extended_base", true));
        parts.add(pistonPart(box(0.375d, 0.375d, 0.75d, 0.625d, 0.625d, 1d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case EAST:
        parts.add(pistonPart(box(0d, 0d, 0d, 0.75d, 1d, 1d), PISTON_BODY, facing, sticky, "extended_base", true));
        parts.add(pistonPart(box(0.75d, 0.375d, 0.375d, 1d, 0.625d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case WEST:
        parts.add(pistonPart(box(0.25d, 0d, 0d, 1d, 1d, 1d), PISTON_BODY, facing, sticky, "extended_base", true));
        parts.add(pistonPart(box(0d, 0.375d, 0.375d, 0.25d, 0.625d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case UP:
        parts.add(pistonPart(box(0d, 0d, 0d, 1d, 0.75d, 1d), PISTON_BODY, facing, sticky, "extended_base", true));
        parts.add(pistonPart(box(0.375d, 0.75d, 0.375d, 0.625d, 1d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      default:
        parts.add(pistonPart(box(0d, 0.25d, 0d, 1d, 1d, 1d), PISTON_BODY, facing, sticky, "extended_base", true));
        parts.add(pistonPart(box(0.375d, 0d, 0.375d, 0.625d, 0.25d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
    }
    return new Shape(parts, false, false);
  }

  private static Shape pistonHead(Map<String, Object> properties) {
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
    boolean sticky = "sticky".equals(property(properties, "type")) || truth(properties, "sticky");
    ArrayList<Part> parts = new ArrayList<Part>();
    switch (facing) {
      case NORTH:
        parts.add(pistonPart(box(0d, 0d, 0d, 1d, 1d, 0.25d), PISTON_HEAD, facing, sticky, "head", true));
        parts.add(pistonPart(box(0.375d, 0.375d, 0.25d, 0.625d, 0.625d, 1d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case SOUTH:
        parts.add(pistonPart(box(0d, 0d, 0.75d, 1d, 1d, 1d), PISTON_HEAD, facing, sticky, "head", true));
        parts.add(pistonPart(box(0.375d, 0.375d, 0d, 0.625d, 0.625d, 0.75d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case EAST:
        parts.add(pistonPart(box(0.75d, 0d, 0d, 1d, 1d, 1d), PISTON_HEAD, facing, sticky, "head", true));
        parts.add(pistonPart(box(0d, 0.375d, 0.375d, 0.75d, 0.625d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case WEST:
        parts.add(pistonPart(box(0d, 0d, 0d, 0.25d, 1d, 1d), PISTON_HEAD, facing, sticky, "head", true));
        parts.add(pistonPart(box(0.25d, 0.375d, 0.375d, 1d, 0.625d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      case UP:
        parts.add(pistonPart(box(0d, 0.75d, 0d, 1d, 1d, 1d), PISTON_HEAD, facing, sticky, "head", true));
        parts.add(pistonPart(box(0.375d, 0d, 0.375d, 0.625d, 0.75d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
      default:
        parts.add(pistonPart(box(0d, 0d, 0d, 1d, 0.25d, 1d), PISTON_HEAD, facing, sticky, "head", true));
        parts.add(pistonPart(box(0.375d, 0.25d, 0.375d, 0.625d, 1d, 0.625d), PISTON_ROD, facing, sticky, "rod", false));
        break;
    }
    return new Shape(parts, false, false);
  }

  private static Part pistonPart(Box value, String material, BlockFace facing, boolean sticky, String kind, boolean outline) {
    EnumMap<BlockFace, FaceSpec> faces = new EnumMap<BlockFace, FaceSpec>(BlockFace.class);
    for (BlockFace face : BlockFace.values()) {
      String texture;
      if ("base".equals(kind)) texture = face == facing ? pistonTop(sticky) : face == facing.opposite() ? pistonBottom() : pistonSide();
      else if ("extended_base".equals(kind)) texture = face == facing ? pistonInner() : face == facing.opposite() ? pistonBottom() : pistonSide();
      else if ("head".equals(kind)) texture = face == facing ? pistonTop(sticky) : face == facing.opposite() ? pistonInner() : pistonSide();
      else texture = face == facing || face == facing.opposite() ? pistonInner() : pistonSide();
      faces.put(face, new FaceSpec(texture, pistonSide().equals(texture) ? pistonSideHeadwardUv(face, facing) : identityUv(), -1));
    }
    return new Part(value, material, ALL_FACES, outline, false, null, faces, null, 0d);
  }

  private static String pistonTop(boolean sticky) {
    return "assets/minecraft/textures/block/" + (sticky ? "piston_top_sticky.png" : "piston_top.png");
  }

  private static String pistonBottom() { return "assets/minecraft/textures/block/piston_bottom.png"; }
  private static String pistonSide() { return "assets/minecraft/textures/block/piston_side.png"; }
  private static String pistonInner() { return "assets/minecraft/textures/block/piston_inner.png"; }

  private static Shape slab(Map<String, Object> properties) {
    String type = property(properties, "type");
    if ("double".equals(type)) return FULL;
    return "top".equals(type)
      ? shape(false, false, part(box(0d, 0.5d, 0d, 1d, 1d, 1d)))
      : shape(false, false, part(box(0d, 0d, 0d, 1d, 0.5d, 1d)));
  }

  private static Shape carpet() {
    return shape(false, false, part(box(0d, 0d, 0d, 1d, 0.0625d, 1d)));
  }

  private static Shape stairs(Map<String, Object> properties) {
    boolean top = "top".equals(property(properties, "half"));
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(top ? box(0d, 0.5d, 0d, 1d, 1d, 1d) : box(0d, 0d, 0d, 1d, 0.5d, 1d)));
    double minY = top ? 0d : 0.5d;
    double maxY = top ? 0.5d : 1d;
    String stairShape = property(properties, "shape");
    if (stairShape.isEmpty()) stairShape = "straight";
    addStairStep(parts, facing, stairShape, minY, maxY);
    return new Shape(parts, false, false);
  }

  private static void addStairStep(List<Part> parts, BlockFace facing, String shape, double minY, double maxY) {
    boolean outer = shape.startsWith("outer_");
    boolean inner = shape.startsWith("inner_");
    boolean left = shape.endsWith("_left");
    if (!outer) parts.add(part(stairBackHalf(facing, minY, maxY)));
    if (!inner && !outer) return;
    BlockFace side = left ? turnLeft(facing) : turnRight(facing);
    if (inner) parts.add(part(stairBackQuarter(facing, side, minY, maxY)));
    else parts.add(part(stairBackQuarter(facing, side.opposite(), minY, maxY)));
  }

  private static Box stairBackHalf(BlockFace facing, double minY, double maxY) {
    if (facing == BlockFace.NORTH) return box(0d, minY, 0.5d, 1d, maxY, 1d);
    if (facing == BlockFace.SOUTH) return box(0d, minY, 0d, 1d, maxY, 0.5d);
    if (facing == BlockFace.EAST) return box(0d, minY, 0d, 0.5d, maxY, 1d);
    return box(0.5d, minY, 0d, 1d, maxY, 1d);
  }

  private static Box stairBackQuarter(BlockFace facing, BlockFace side, double minY, double maxY) {
    boolean westHalf = facing == BlockFace.EAST || side == BlockFace.WEST;
    boolean eastHalf = facing == BlockFace.WEST || side == BlockFace.EAST;
    boolean northHalf = facing == BlockFace.SOUTH || side == BlockFace.NORTH;
    boolean southHalf = facing == BlockFace.NORTH || side == BlockFace.SOUTH;
    double minX = westHalf ? 0d : 0.5d;
    double maxX = eastHalf ? 1d : 0.5d;
    double minZ = northHalf ? 0d : 0.5d;
    double maxZ = southHalf ? 1d : 0.5d;
    if (minX == maxX) { minX = side == BlockFace.WEST ? 0d : 0.5d; maxX = minX + 0.5d; }
    if (minZ == maxZ) { minZ = side == BlockFace.NORTH ? 0d : 0.5d; maxZ = minZ + 0.5d; }
    return box(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private static BlockFace turnLeft(BlockFace facing) {
    if (facing == BlockFace.NORTH) return BlockFace.WEST;
    if (facing == BlockFace.WEST) return BlockFace.SOUTH;
    if (facing == BlockFace.SOUTH) return BlockFace.EAST;
    return BlockFace.NORTH;
  }

  private static BlockFace turnRight(BlockFace facing) {
    return turnLeft(facing).opposite();
  }

  private static Shape banner(String name, Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    if (name.endsWith("_wall_banner")) {
      BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
      if (facing == BlockFace.NORTH) parts.add(part(box(0.0625d, 0.125d, 0.02d, 0.9375d, 0.9375d, 0.075d)));
      else if (facing == BlockFace.SOUTH) parts.add(part(box(0.0625d, 0.125d, 0.925d, 0.9375d, 0.9375d, 0.98d)));
      else if (facing == BlockFace.EAST) parts.add(part(box(0.925d, 0.125d, 0.0625d, 0.98d, 0.9375d, 0.9375d)));
      else parts.add(part(box(0.02d, 0.125d, 0.0625d, 0.075d, 0.9375d, 0.9375d)));
    } else {
      parts.add(part(box(0.46d, 0d, 0.46d, 0.54d, 1d, 0.54d)));
      parts.add(part(box(0.0625d, 0.18d, 0.46d, 0.9375d, 0.95d, 0.54d)));
      int rotation = Math.floorMod(number(properties, "rotation", 0), 16);
      int quarter = Math.floorMod((int) Math.round(rotation / 4d), 4);
      BlockFace facing = quarter == 1 ? BlockFace.WEST : quarter == 2 ? BlockFace.NORTH : quarter == 3 ? BlockFace.EAST : BlockFace.SOUTH;
      return rotateHorizontal(parts, facing);
    }
    return new Shape(parts, false, false);
  }

  private static boolean isSign(String name) {
    return name.endsWith("_wall_hanging_sign") || name.endsWith("_hanging_sign")
      || name.endsWith("_wall_sign") || name.endsWith("_sign");
  }

  private static Shape sign(String name, Map<String, Object> properties) {
    boolean wallHanging = name.endsWith("_wall_hanging_sign");
    boolean hanging = wallHanging || name.endsWith("_hanging_sign");
    BlockFace facing = wallHanging || name.endsWith("_wall_sign")
      ? BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH)
      : signRotation(properties);
    ArrayList<Part> parts = new ArrayList<Part>();
    if (wallHanging || name.endsWith("_wall_sign")) {
      double low = hanging ? 0.22d : 0.16d;
      double high = hanging ? 0.78d : 0.86d;
      parts.add(part(box(0.0625d, low, 0.015d, 0.9375d, high, 0.085d), SIGN_BOARD, ALL_FACES, true, false));
      addSignText(parts, low + 0.07d, high - 0.07d, 0.086d, 0.092d, BlockFace.NORTH);
      if (hanging) {
        parts.add(part(box(0.17d, 0.78d, 0.035d, 0.25d, 1d, 0.115d), SIGN_POST, ALL_FACES, false, false));
        parts.add(part(box(0.75d, 0.78d, 0.035d, 0.83d, 1d, 0.115d), SIGN_POST, ALL_FACES, false, false));
      }
      return rotateHorizontal(parts, facing);
    }
    double boardLow = hanging ? 0.26d : 0.42d;
    double boardHigh = hanging ? 0.80d : 0.94d;
    parts.add(part(box(0.0625d, boardLow, 0.46d, 0.9375d, boardHigh, 0.54d), SIGN_BOARD, ALL_FACES, true, false));
    addSignText(parts, boardLow + 0.06d, boardHigh - 0.06d, 0.541d, 0.547d, BlockFace.SOUTH);
    addSignText(parts, boardLow + 0.06d, boardHigh - 0.06d, 0.453d, 0.459d, BlockFace.NORTH);
    if (hanging) {
      parts.add(part(box(0.17d, 0.80d, 0.47d, 0.25d, 1d, 0.55d), SIGN_POST, ALL_FACES, false, false));
      parts.add(part(box(0.75d, 0.80d, 0.47d, 0.83d, 1d, 0.55d), SIGN_POST, ALL_FACES, false, false));
    } else {
      parts.add(part(box(0.46d, 0d, 0.46d, 0.54d, boardLow, 0.54d), SIGN_POST, ALL_FACES, false, false));
    }
    return rotateHorizontal(parts, facing);
  }

  private static void addSignText(List<Part> parts, double low, double high, double minZ, double maxZ, BlockFace face) {
    EnumMap<BlockFace, FaceSpec> faces = new EnumMap<BlockFace, FaceSpec>(BlockFace.class);
    faces.put(face, new FaceSpec("lrs:generated/sign_text", identityUv(), -1));
    parts.add(new Part(box(0.13d, low, minZ, 0.87d, high, maxZ), SIGN_TEXT, mask(face), false, false, null, faces, null, 0d));
  }

  private static BlockFace signRotation(Map<String, Object> properties) {
    int rotation = Math.floorMod(number(properties, "rotation", 0), 16);
    int quarter = Math.floorMod((int) Math.round(rotation / 4d), 4);
    if (quarter == 0) return BlockFace.SOUTH;
    if (quarter == 1) return BlockFace.WEST;
    if (quarter == 2) return BlockFace.NORTH;
    return BlockFace.EAST;
  }

  private static Shape torch(LitematicModel.Block block) {
    Map<String, Object> properties = block.properties;
    String name = localName(block.name);
    String material = name.contains("redstone") ? ("false".equals(property(properties, "lit")) ? TORCH_OFF : TORCH_ON) : BLOCK;
    if (name.contains("wall_torch")) {
      BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
      Box body;
      switch (facing) {
        case SOUTH: body = box(0.40d, 0.25d, 0.50d, 0.60d, 0.78d, 0.82d); break;
        case EAST: body = box(0.50d, 0.25d, 0.40d, 0.82d, 0.78d, 0.60d); break;
        case WEST: body = box(0.18d, 0.25d, 0.40d, 0.50d, 0.78d, 0.60d); break;
        default: body = box(0.40d, 0.25d, 0.18d, 0.60d, 0.78d, 0.50d); break;
      }
      return shape(false, false, part(body, material, ALL_FACES, false, false));
    }
    ArrayList<Part> parts = new ArrayList<Part>();
    torchParts(parts, 0.5d, 0.5d, 0d, 0.875d, TORCH_ON.equals(material));
    return new Shape(parts, false, false);
  }

  private static Shape button(Map<String, Object> properties) {
    String face = property(properties, "face");
    if ("floor".equals(face)) return shape(false, false, part(box(0.3125d, 0d, 0.25d, 0.6875d, 0.125d, 0.75d)));
    if ("ceiling".equals(face)) return shape(false, false, part(box(0.3125d, 0.875d, 0.25d, 0.6875d, 1d, 0.75d)));
    switch (BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH)) {
      case SOUTH: return shape(false, false, part(box(0.25d, 0.25d, 0.875d, 0.75d, 0.75d, 1d)));
      case EAST: return shape(false, false, part(box(0.875d, 0.25d, 0.25d, 1d, 0.75d, 0.75d)));
      case WEST: return shape(false, false, part(box(0d, 0.25d, 0.25d, 0.125d, 0.75d, 0.75d)));
      default: return shape(false, false, part(box(0.25d, 0.25d, 0d, 0.75d, 0.75d, 0.125d)));
    }
  }

  private static Shape lever(Map<String, Object> properties) {
    String face = property(properties, "face");
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
    ArrayList<Part> parts = new ArrayList<Part>();
    if ("wall".equals(face)) {
      switch (facing) {
        case SOUTH: parts.add(part(box(0.25d, 0.25d, 0.875d, 0.75d, 0.75d, 1d))); parts.add(part(box(0.43d, 0.35d, 0.62d, 0.57d, 0.82d, 0.88d))); break;
        case EAST: parts.add(part(box(0.875d, 0.25d, 0.25d, 1d, 0.75d, 0.75d))); parts.add(part(box(0.62d, 0.35d, 0.43d, 0.88d, 0.82d, 0.57d))); break;
        case WEST: parts.add(part(box(0d, 0.25d, 0.25d, 0.125d, 0.75d, 0.75d))); parts.add(part(box(0.12d, 0.35d, 0.43d, 0.38d, 0.82d, 0.57d))); break;
        default: parts.add(part(box(0.25d, 0.25d, 0d, 0.75d, 0.75d, 0.125d))); parts.add(part(box(0.43d, 0.35d, 0.12d, 0.57d, 0.82d, 0.38d))); break;
      }
    } else if ("ceiling".equals(face)) {
      parts.add(part(box(0.25d, 0.875d, 0.25d, 0.75d, 1d, 0.75d)));
      parts.add(part(box(0.43d, 0.48d, 0.43d, 0.57d, 0.88d, 0.57d)));
    } else {
      parts.add(part(box(0.25d, 0d, 0.25d, 0.75d, 0.125d, 0.75d)));
      parts.add(part(box(0.43d, 0.12d, 0.43d, 0.57d, 0.62d, 0.57d)));
    }
    return new Shape(parts, false, false);
  }

  private static Shape pressurePlate(Map<String, Object> properties) {
    double height = number(properties, "power", truth(properties, "powered") ? 15 : 0) > 0 ? 0.03125d : 0.0625d;
    return shape(false, false, part(box(0.063d, 0d, 0.063d, 0.937d, height, 0.937d)));
  }

  private static Shape trapdoor(Map<String, Object> properties) {
    boolean open = truth(properties, "open");
    if (!open) return "top".equals(property(properties, "half"))
      ? shape(false, false, part(box(0d, 0.8125d, 0d, 1d, 1d, 1d)))
      : shape(false, false, part(box(0d, 0d, 0d, 1d, 0.1875d, 1d)));
    switch (BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH)) {
      case SOUTH: return shape(false, false, part(box(0d, 0d, 0.8125d, 1d, 1d, 1d)));
      case EAST: return shape(false, false, part(box(0.8125d, 0d, 0d, 1d, 1d, 1d)));
      case WEST: return shape(false, false, part(box(0d, 0d, 0d, 0.1875d, 1d, 1d)));
      default: return shape(false, false, part(box(0d, 0d, 0d, 1d, 1d, 0.1875d)));
    }
  }

  private static Shape fence(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0.375d, 0d, 0.375d, 0.625d, 1d, 0.625d)));
    if (truth(properties, "north")) parts.add(part(box(0.375d, 0.375d, 0d, 0.625d, 0.9375d, 0.5d)));
    if (truth(properties, "south")) parts.add(part(box(0.375d, 0.375d, 0.5d, 0.625d, 0.9375d, 1d)));
    if (truth(properties, "east")) parts.add(part(box(0.5d, 0.375d, 0.375d, 1d, 0.9375d, 0.625d)));
    if (truth(properties, "west")) parts.add(part(box(0d, 0.375d, 0.375d, 0.5d, 0.9375d, 0.625d)));
    return new Shape(parts, false, false);
  }

  private static Shape wall(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    if (truth(properties, "up") || noWallConnections(properties)) parts.add(part(box(0.25d, 0d, 0.25d, 0.75d, 1d, 0.75d)));
    wallArm(parts, properties, "north", box(0.3125d, 0d, 0d, 0.6875d, 0.8125d, 0.5d), box(0.3125d, 0d, 0d, 0.6875d, 1d, 0.5d));
    wallArm(parts, properties, "south", box(0.3125d, 0d, 0.5d, 0.6875d, 0.8125d, 1d), box(0.3125d, 0d, 0.5d, 0.6875d, 1d, 1d));
    wallArm(parts, properties, "east", box(0.5d, 0d, 0.3125d, 1d, 0.8125d, 0.6875d), box(0.5d, 0d, 0.3125d, 1d, 1d, 0.6875d));
    wallArm(parts, properties, "west", box(0d, 0d, 0.3125d, 0.5d, 0.8125d, 0.6875d), box(0d, 0d, 0.3125d, 0.5d, 1d, 0.6875d));
    return new Shape(parts, false, false);
  }

  private static Shape panes(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    boolean any = truth(properties, "north") || truth(properties, "south") || truth(properties, "east") || truth(properties, "west");
    if (!any) parts.add(part(box(0.4375d, 0d, 0.4375d, 0.5625d, 1d, 0.5625d)));
    if (truth(properties, "north")) parts.add(part(box(0.4375d, 0d, 0d, 0.5625d, 1d, 0.5d)));
    if (truth(properties, "south")) parts.add(part(box(0.4375d, 0d, 0.5d, 0.5625d, 1d, 1d)));
    if (truth(properties, "east")) parts.add(part(box(0.5d, 0d, 0.4375d, 1d, 1d, 0.5625d)));
    if (truth(properties, "west")) parts.add(part(box(0d, 0d, 0.4375d, 0.5d, 1d, 0.5625d)));
    return new Shape(parts, false, false);
  }

  private static Shape hopper(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0d, 0.625d, 0d, 1d, 1d, 0.1875d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0d, 0.625d, 0.8125d, 1d, 1d, 1d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0d, 0.625d, 0.1875d, 0.1875d, 1d, 0.8125d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0.8125d, 0.625d, 0.1875d, 1d, 1d, 0.8125d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0.1875d, 0.555d, 0.1875d, 0.8125d, 0.57d, 0.8125d), HOPPER_INSIDE, TOP_FACE, false));
    parts.add(part(box(0.125d, 0.25d, 0.125d, 0.875d, 0.625d, 0.25d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0.125d, 0.25d, 0.75d, 0.875d, 0.625d, 0.875d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0.125d, 0.25d, 0.25d, 0.25d, 0.625d, 0.75d), HOPPER_OUTSIDE, ALL_FACES, true));
    parts.add(part(box(0.75d, 0.25d, 0.25d, 0.875d, 0.625d, 0.75d), HOPPER_OUTSIDE, ALL_FACES, true));
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.DOWN);
    switch (facing) {
      case NORTH: parts.add(part(box(0.375d, 0.125d, 0d, 0.625d, 0.375d, 0.5d), HOPPER_OUTSIDE, ALL_FACES, true)); break;
      case SOUTH: parts.add(part(box(0.375d, 0.125d, 0.5d, 0.625d, 0.375d, 1d), HOPPER_OUTSIDE, ALL_FACES, true)); break;
      case EAST: parts.add(part(box(0.5d, 0.125d, 0.375d, 1d, 0.375d, 0.625d), HOPPER_OUTSIDE, ALL_FACES, true)); break;
      case WEST: parts.add(part(box(0d, 0.125d, 0.375d, 0.5d, 0.375d, 0.625d), HOPPER_OUTSIDE, ALL_FACES, true)); break;
      default: parts.add(part(box(0.375d, 0d, 0.375d, 0.625d, 0.375d, 0.625d), HOPPER_OUTSIDE, ALL_FACES, true)); break;
    }
    return new Shape(parts, false, false);
  }

  private static Shape chest(LitematicModel model, LitematicModel.Block block) {
    ChestPair pair = chestPair(model, block);
    return pair == null ? chestSingle(block.properties) : chestDouble(block.properties, pair);
  }

  private static Shape chestSingle(Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0.0625d, 0d, 0.0625d, 0.9375d, 0.625d, 0.9375d), CHEST_BOTTOM, ALL_FACES, false, false));
    parts.add(part(box(0.0625d, 0.625d, 0.0625d, 0.9375d, 0.9375d, 0.9375d), CHEST_LID, ALL_FACES, false, false));
    parts.add(part(box(0.4375d, 0.4375d, 0.025d, 0.5625d, 0.6875d, 0.0875d), CHEST_LATCH, ALL_FACES, false, false));
    return rotateHorizontal(parts, BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH));
  }

  private static Shape chestDouble(Map<String, Object> properties, ChestPair pair) {
    int outsideFaces = ALL_FACES & ~mask(pair.toPartner);
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(doubleChestBox(pair.toPartner, 0d, 0.625d), CHEST_DOUBLE_BOTTOM, outsideFaces, false, false));
    parts.add(part(doubleChestBox(pair.toPartner, 0.625d, 0.9375d), CHEST_DOUBLE_LID, outsideFaces, false, false));
    if (pair.latchOwner) parts.add(doubleChestLatch(properties, pair.toPartner));
    return new Shape(parts, false, false);
  }

  private static Box doubleChestBox(BlockFace partner, double minY, double maxY) {
    double minX = 0.0625d, maxX = 0.9375d, minZ = 0.0625d, maxZ = 0.9375d;
    if (partner == BlockFace.EAST) maxX = 1d;
    else if (partner == BlockFace.WEST) minX = 0d;
    else if (partner == BlockFace.SOUTH) maxZ = 1d;
    else if (partner == BlockFace.NORTH) minZ = 0d;
    return box(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private static Part doubleChestLatch(Map<String, Object> properties, BlockFace partner) {
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
    boolean alongX = partner == BlockFace.EAST || partner == BlockFace.WEST;
    double seamX = partner == BlockFace.EAST ? 1d : partner == BlockFace.WEST ? 0d : 0.5d;
    double seamZ = partner == BlockFace.SOUTH ? 1d : partner == BlockFace.NORTH ? 0d : 0.5d;
    if (facing == BlockFace.SOUTH) return part(box(alongX ? seamX - 0.0625d : 0.4375d, 0.4375d, 0.9125d, alongX ? seamX + 0.0625d : 0.5625d, 0.6875d, 0.975d), CHEST_LATCH, ALL_FACES, false, false);
    if (facing == BlockFace.EAST) return part(box(0.9125d, 0.4375d, alongX ? 0.4375d : seamZ - 0.0625d, 0.975d, 0.6875d, alongX ? 0.5625d : seamZ + 0.0625d), CHEST_LATCH, ALL_FACES, false, false);
    if (facing == BlockFace.WEST) return part(box(0.025d, 0.4375d, alongX ? 0.4375d : seamZ - 0.0625d, 0.0875d, 0.6875d, alongX ? 0.5625d : seamZ + 0.0625d), CHEST_LATCH, ALL_FACES, false, false);
    return part(box(alongX ? seamX - 0.0625d : 0.4375d, 0.4375d, 0.025d, alongX ? seamX + 0.0625d : 0.5625d, 0.6875d, 0.0875d), CHEST_LATCH, ALL_FACES, false, false);
  }

  private static ChestPair chestPair(LitematicModel model, LitematicModel.Block block) {
    if (model == null || localName(block.name).contains("ender")) return null;
    String type = property(block.properties, "type");
    if (!"left".equals(type) && !"right".equals(type)) return null;
    BlockFace facing = BlockFace.fromProperty(block.properties.get("facing"), BlockFace.NORTH);
    for (BlockFace direction : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
      LitematicModel.Block neighbor = model.blockAt(block.x + direction.dx, block.y, block.z + direction.dz);
      if (neighbor == null || !block.name.equals(neighbor.name)) continue;
      if (BlockFace.fromProperty(neighbor.properties.get("facing"), BlockFace.NORTH) != facing) continue;
      String other = property(neighbor.properties, "type");
      if (("left".equals(type) && "right".equals(other)) || ("right".equals(type) && "left".equals(other))) {
        return new ChestPair(direction, direction == BlockFace.EAST || direction == BlockFace.SOUTH);
      }
    }
    return null;
  }

  private static final class ChestPair {
    final BlockFace toPartner;
    final boolean latchOwner;

    ChestPair(BlockFace toPartner, boolean latchOwner) {
      this.toPartner = toPartner;
      this.latchOwner = latchOwner;
    }
  }

  private static Shape minecart(String name, Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    parts.add(part(box(0.06d, 0.05d, 0.12d, 0.94d, 0.19d, 0.88d), MINECART, ALL_FACES, true, false));
    parts.add(part(box(0.08d, 0.19d, 0.12d, 0.18d, 0.56d, 0.88d), MINECART, ALL_FACES, true, false));
    parts.add(part(box(0.82d, 0.19d, 0.12d, 0.92d, 0.56d, 0.88d), MINECART, ALL_FACES, true, false));
    parts.add(part(box(0.18d, 0.19d, 0.12d, 0.82d, 0.56d, 0.22d), MINECART, ALL_FACES, true, false));
    parts.add(part(box(0.18d, 0.19d, 0.78d, 0.82d, 0.56d, 0.88d), MINECART, ALL_FACES, true, false));
    parts.add(part(box(0.10d, 0d, 0.18d, 0.22d, 0.11d, 0.30d), MINECART_WHEEL, ALL_FACES, false, false));
    parts.add(part(box(0.78d, 0d, 0.18d, 0.90d, 0.11d, 0.30d), MINECART_WHEEL, ALL_FACES, false, false));
    parts.add(part(box(0.10d, 0d, 0.70d, 0.22d, 0.11d, 0.82d), MINECART_WHEEL, ALL_FACES, false, false));
    parts.add(part(box(0.78d, 0d, 0.70d, 0.90d, 0.11d, 0.82d), MINECART_WHEEL, ALL_FACES, false, false));
    if (name.contains("chest_minecart")) {
      parts.add(part(box(0.18d, 0.52d, 0.18d, 0.82d, 1.10d, 0.82d), MINECART_CARGO, ALL_FACES, true, false));
    } else if (name.contains("hopper_minecart")) {
      parts.add(part(box(0.18d, 0.48d, 0.18d, 0.82d, 0.77d, 0.82d), HOPPER_OUTSIDE, ALL_FACES, true, true));
    } else if (name.contains("tnt_minecart")) {
      parts.add(part(box(0.18d, 0.49d, 0.18d, 0.82d, 1.13d, 0.82d), MINECART_CARGO, ALL_FACES, true, false));
    } else if (name.contains("furnace_minecart") || name.contains("command_block_minecart") || name.contains("spawner_minecart")) {
      parts.add(part(box(0.20d, 0.48d, 0.20d, 0.80d, 1.08d, 0.80d), MINECART_CARGO, ALL_FACES, true, false));
    }
    return rotateHorizontal(parts, BlockFace.fromProperty(properties.get("facing"), BlockFace.SOUTH));
  }

  private static Shape entity(String name, Map<String, Object> properties) {
    ArrayList<Part> parts = new ArrayList<Part>();
    if (name.contains("boat")) {
      parts.add(part(box(0.05d, 0.12d, 0.12d, 0.95d, 0.31d, 0.88d), ENTITY, ALL_FACES, true, false));
      parts.add(part(box(0.10d, 0.31d, 0.18d, 0.90d, 0.52d, 0.29d), ENTITY, ALL_FACES, true, false));
      parts.add(part(box(0.10d, 0.31d, 0.71d, 0.90d, 0.52d, 0.82d), ENTITY, ALL_FACES, true, false));
      return rotateHorizontal(parts, BlockFace.fromProperty(properties.get("facing"), BlockFace.SOUTH));
    }
    if (name.contains("armor_stand")) {
      parts.add(part(box(0.43d, 0d, 0.43d, 0.57d, 1.45d, 0.57d), ENTITY, ALL_FACES, false, false));
      parts.add(part(box(0.25d, 0.04d, 0.43d, 0.75d, 0.10d, 0.57d), ENTITY, ALL_FACES, false, false));
      parts.add(part(box(0.43d, 0.04d, 0.25d, 0.57d, 0.10d, 0.75d), ENTITY, ALL_FACES, false, false));
      return new Shape(parts, false, false);
    }
    if (name.contains("item_frame")) return itemFrame(name, properties);
    if (name.contains("painting")) {
      parts.add(part(box(0.05d, 0.05d, 0.45d, 0.95d, 0.95d, 0.55d), ENTITY, ALL_FACES, true, false));
      BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.SOUTH);
      return facing == BlockFace.UP || facing == BlockFace.DOWN ? new Shape(parts, false, false) : rotateHorizontal(parts, facing);
    }
    if (isSign(name)) return sign(name, properties);
    if (name.equals("item") || name.endsWith(":item")) {
      parts.add(part(box(0.34d, 0.08d, 0.34d, 0.66d, 0.40d, 0.66d), ITEM_ENTITY, ALL_FACES, false, false));
      return new Shape(parts, false, false);
    }
    parts.add(part(box(0.25d, 0d, 0.25d, 0.75d, 0.80d, 0.75d), ENTITY, ALL_FACES, true, false));
    return new Shape(parts, false, false);
  }

  private static Shape itemFrame(String name, Map<String, Object> properties) {
    BlockFace facing = BlockFace.fromProperty(properties.get("facing"), BlockFace.NORTH);
    boolean invisible = truth(properties, "invisible") || truth(properties, "Invisible");
    boolean hasItem = !property(properties, "lrs_item_id").isEmpty();
    ArrayList<Part> parts = new ArrayList<Part>();
    if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
      double front = facing == BlockFace.UP ? 0.54d : 0.46d;
      double back = facing == BlockFace.UP ? 0.46d : 0.54d;
      if (!invisible) parts.add(part(box(0.04d, Math.min(front, back), 0.04d, 0.96d, Math.max(front, back), 0.96d), ITEM_FRAME, ALL_FACES, true, false));
      if (hasItem) {
        double itemBack = facing == BlockFace.UP ? 0.541d : 0.459d;
        double itemFront = facing == BlockFace.UP ? 0.548d : 0.452d;
        parts.add(part(box(0.19d, Math.min(itemBack, itemFront), 0.19d, 0.81d, Math.max(itemBack, itemFront), 0.81d), FRAME_ITEM, mask(facing), false, false));
      }
      return new Shape(parts, false, false);
    }
    if (!invisible) parts.add(part(box(0.04d, 0.04d, 0.46d, 0.96d, 0.96d, 0.54d), ITEM_FRAME, ALL_FACES, true, false));
    if (hasItem) parts.add(part(box(0.19d, 0.19d, 0.452d, 0.81d, 0.81d, 0.459d), FRAME_ITEM, mask(BlockFace.NORTH), false, false));
    return rotateHorizontal(parts, facing);
  }

  private static Shape rotateHorizontal(List<Part> source, BlockFace facing) {
    ArrayList<Part> parts = new ArrayList<Part>(source.size());
    for (Part part : source) {
      int faces = 0;
      EnumMap<BlockFace, FaceSpec> specs = new EnumMap<BlockFace, FaceSpec>(BlockFace.class);
      for (BlockFace face : BlockFace.values()) {
        if (!part.shows(face)) continue;
        BlockFace rotated = rotateFace(face, facing);
        faces |= 1 << rotated.ordinal();
        FaceSpec spec = part.faceSpec(face);
        if (spec != null) specs.put(rotated, spec);
      }
      parts.add(new Part(rotateNorth(part.box, facing), part.material, faces, part.outline, part.cropTexture,
        rotateFace(part.slopeUp, facing), specs, part.topHeights, part.textureAngleDeg));
    }
    return new Shape(parts, false, false);
  }

  private static BlockFace rotateFace(BlockFace face, BlockFace facing) {
    if (face == null || face == BlockFace.UP || face == BlockFace.DOWN) return face;
    if (facing == BlockFace.EAST) return face == BlockFace.NORTH ? BlockFace.EAST : face == BlockFace.EAST ? BlockFace.SOUTH : face == BlockFace.SOUTH ? BlockFace.WEST : BlockFace.NORTH;
    if (facing == BlockFace.SOUTH) return face.opposite();
    if (facing == BlockFace.WEST) return face == BlockFace.NORTH ? BlockFace.WEST : face == BlockFace.WEST ? BlockFace.SOUTH : face == BlockFace.SOUTH ? BlockFace.EAST : BlockFace.NORTH;
    return face;
  }

  private static Box rotateNorth(Box value, BlockFace facing) {
    switch (facing) {
      case EAST: return box(1d - value.maxZ, value.minY, value.minX, 1d - value.minZ, value.maxY, value.maxX);
      case SOUTH: return box(1d - value.maxX, value.minY, 1d - value.maxZ, 1d - value.minX, value.maxY, 1d - value.minZ);
      case WEST: return box(value.minZ, value.minY, 1d - value.maxX, value.maxZ, value.maxY, 1d - value.minX);
      default: return value;
    }
  }

  private static Box pillar(double centerX, double centerZ, double width, double minY, double maxY) {
    double half = width * 0.5d;
    return box(centerX - half, minY, centerZ - half, centerX + half, maxY, centerZ + half);
  }

  private static void torchParts(List<Part> parts, double centerX, double centerZ, double minY, double maxY, boolean lit) {
    double headHeight = Math.min(0.19d, Math.max(0.11d, (maxY - minY) * 0.34d));
    double stemTop = Math.max(minY + 0.04d, maxY - headHeight);
    parts.add(part(pillar(centerX, centerZ, 0.105d, minY, stemTop), TORCH_STEM, ALL_FACES, false, false));
    parts.add(part(pillar(centerX, centerZ, 0.22d, stemTop, maxY), lit ? TORCH_HEAD_ON : TORCH_HEAD_OFF, ALL_FACES, false, false));
  }

  private static void wallArm(List<Part> parts, Map<String, Object> properties, String key, Box low, Box tall) {
    String value = property(properties, key);
    if ("low".equals(value) || "true".equals(value)) parts.add(part(low));
    else if ("tall".equals(value)) parts.add(part(tall));
  }

  private static boolean noWallConnections(Map<String, Object> properties) {
    return emptyOrNone(property(properties, "north")) && emptyOrNone(property(properties, "south"))
      && emptyOrNone(property(properties, "east")) && emptyOrNone(property(properties, "west"));
  }

  private static boolean emptyOrNone(String value) {
    return value.isEmpty() || "none".equals(value) || "false".equals(value);
  }

  private static double[] fluidCorners(LitematicModel model, LitematicModel.Block block, String name) {
    if (model == null) {
      double height = fluidCellHeight(block, null, name);
      return new double[] { height, height, height, height };
    }
    return new double[] {
      fluidCorner(model, block.x, block.y, block.z, name, -1, -1),
      fluidCorner(model, block.x, block.y, block.z, name, 1, -1),
      fluidCorner(model, block.x, block.y, block.z, name, 1, 1),
      fluidCorner(model, block.x, block.y, block.z, name, -1, 1)
    };
  }

  private static double fluidCorner(LitematicModel model, int x, int y, int z, String name, int xSide, int zSide) {
    int[][] offsets = new int[][] { { 0, 0 }, { xSide, 0 }, { 0, zSide }, { xSide, zSide } };
    double sum = 0d;
    double weight = 0d;
    for (int[] offset : offsets) {
      LitematicModel.Block value = model.blockAt(x + offset[0], y, z + offset[1]);
      LitematicModel.Block above = model.blockAt(x + offset[0], y + 1, z + offset[1]);
      double height = fluidCellHeight(value, above, name);
      if (height < 0d) continue;
      if (height >= 0.999d) return 1d;
      int level = value == null ? 0 : number(value.properties, "level", 0);
      double amount = level == 0 ? 10d : 1d;
      sum += height * amount;
      weight += amount;
    }
    return weight == 0d ? 0.12d : Math.max(0.12d, sum / weight);
  }

  private static double fluidCellHeight(LitematicModel.Block value, LitematicModel.Block above, String name) {
    if (value == null || !name.equals(localName(value.name))) return -1d;
    if (above != null && name.equals(localName(above.name))) return 1d;
    int level = clamp(number(value.properties, "level", 0), 0, 15);
    if (level == 0 || level >= 8) return 8d / 9d;
    return Math.max(1d / 9d, (8d - level) / 9d);
  }

  private static double fluidRotation(LitematicModel model, LitematicModel.Block block, String name) {
    if (model == null) return 0d;
    double west = neighborFluidHeight(model, block.x - 1, block.y, block.z, name);
    double east = neighborFluidHeight(model, block.x + 1, block.y, block.z, name);
    double north = neighborFluidHeight(model, block.x, block.y, block.z - 1, name);
    double south = neighborFluidHeight(model, block.x, block.y, block.z + 1, name);
    double dx = west - east;
    double dz = north - south;
    if (Math.abs(dx) + Math.abs(dz) < 0.01d) return 0d;
    return Math.toDegrees(Math.atan2(dz, dx)) - 90d;
  }

  private static double neighborFluidHeight(LitematicModel model, int x, int y, int z, String name) {
    double value = fluidCellHeight(model.blockAt(x, y, z), model.blockAt(x, y + 1, z), name);
    return value < 0d ? 0d : value;
  }

  private static double spread(double[] values) {
    double low = Double.POSITIVE_INFINITY;
    double high = Double.NEGATIVE_INFINITY;
    for (double value : values) {
      low = Math.min(low, value);
      high = Math.max(high, value);
    }
    return high - low;
  }

  static Box modelBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    return box(minX, minY, minZ, maxX, maxY, maxZ);
  }

  static FaceSpec modelFace(String resource, double[] uv, int tintIndex) {
    return new FaceSpec(resource, uv, tintIndex);
  }

  static Part modelPart(Box box, Map<BlockFace, FaceSpec> faces) {
    int value = 0;
    for (BlockFace face : faces.keySet()) value |= 1 << face.ordinal();
    return new Part(box, BLOCK, value, false, false, null, faces, null, 0);
  }

  static Shape modelShape(List<Part> parts, boolean fullCube, boolean occluding) {
    return new Shape(new ArrayList<Part>(parts), fullCube, occluding);
  }

  private static double[] identityUv() {
    return new double[] { 0d, 0d, 1d, 0d, 1d, 1d, 0d, 1d };
  }

  static double[] pistonSideHeadwardUv(BlockFace face, BlockFace facing) {
    if (face == facing || face == facing.opposite()) return identityUv();
    BlockFace u = faceUAxis(face);
    BlockFace v = faceVAxis(face);
    if (facing == u) return quarterUv(1);
    if (facing == u.opposite()) return quarterUv(3);
    if (facing == v) return new double[] { 0d, 1d, 1d, 1d, 1d, 0d, 0d, 0d };
    if (facing == v.opposite()) return new double[] { 1d, 0d, 0d, 0d, 0d, 1d, 1d, 1d };
    return identityUv();
  }

  static BlockFace pistonSideHeadwardDirection(BlockFace face, double[] uv) {
    if (uv == null || uv.length < 8) return null;
    double alongU = uv[3] - uv[1];
    double alongV = uv[7] - uv[1];
    if (alongU < -0.5d) return faceUAxis(face);
    if (alongU > 0.5d) return faceUAxis(face).opposite();
    if (alongV < -0.5d) return faceVAxis(face);
    if (alongV > 0.5d) return faceVAxis(face).opposite();
    return null;
  }

  private static BlockFace faceUAxis(BlockFace face) {
    if (face == BlockFace.EAST) return BlockFace.SOUTH;
    if (face == BlockFace.WEST) return BlockFace.NORTH;
    if (face == BlockFace.SOUTH) return BlockFace.WEST;
    return BlockFace.EAST;
  }

  private static BlockFace faceVAxis(BlockFace face) {
    if (face == BlockFace.UP) return BlockFace.SOUTH;
    if (face == BlockFace.DOWN) return BlockFace.NORTH;
    return BlockFace.DOWN;
  }

  private static double[] quarterUv(int quarter) {
    int value = Math.floorMod(quarter, 4);
    if (value == 1) return new double[] { 0d, 1d, 0d, 0d, 1d, 0d, 1d, 1d };
    if (value == 2) return new double[] { 1d, 1d, 0d, 1d, 0d, 0d, 1d, 0d };
    if (value == 3) return new double[] { 1d, 0d, 1d, 1d, 0d, 1d, 0d, 0d };
    return identityUv();
  }

  private static Shape shape(boolean fullCube, boolean occluding, Part... parts) {
    ArrayList<Part> values = new ArrayList<Part>();
    Collections.addAll(values, parts);
    return new Shape(values, fullCube, occluding);
  }

  private static Part part(Box box) {
    return new Part(box, BLOCK, ALL_FACES, true, true);
  }

  private static Part part(Box box, String material, int faces, boolean outline) {
    return new Part(box, material, faces, outline, defaultCrop(material));
  }

  private static Part part(Box box, String material, int faces, boolean outline, boolean cropTexture) {
    return new Part(box, material, faces, outline, cropTexture);
  }

  private static Part part(Box box, String material, int faces, boolean outline, boolean cropTexture, BlockFace slopeUp) {
    return new Part(box, material, faces, outline, cropTexture, slopeUp);
  }

  private static boolean defaultCrop(String material) {
    return BLOCK.equals(material) || REPEATER_BASE.equals(material) || COMPARATOR_BASE.equals(material)
      || PISTON_BODY.equals(material) || PISTON_HEAD.equals(material) || CHEST_BOTTOM.equals(material) || CHEST_LID.equals(material)
      || CHEST_DOUBLE_BOTTOM.equals(material) || CHEST_DOUBLE_LID.equals(material);
  }

  private static Box box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    return new Box(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private static int mask(BlockFace... faces) {
    int value = 0;
    for (BlockFace face : faces) value |= 1 << face.ordinal();
    return value;
  }

  private static String localName(String raw) {
    String value = raw == null ? "" : raw;
    int colon = value.indexOf(':');
    return (colon >= 0 ? value.substring(colon + 1) : value).toLowerCase(Locale.ROOT);
  }

  private static String property(Map<String, Object> properties, String key) {
    Object value = properties.get(key);
    return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
  }

  private static boolean truth(Map<String, Object> properties, String key) {
    String value = property(properties, key);
    return "true".equals(value) || "1".equals(value);
  }

  private static int number(Map<String, Object> properties, String key, int fallback) {
    try {
      Object value = properties.get(key);
      return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static boolean isTransparent(String name) {
    return name.contains("glass") || name.contains("water") || name.contains("ice") || name.contains("leaves") || name.contains("barrier")
      || name.contains("slime") || name.contains("honey");
  }
}

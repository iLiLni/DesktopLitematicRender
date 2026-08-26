package studio.litematicrender.worker;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;

public final class RendererSelfTest {
  private RendererSelfTest() {
  }

  public static void main(String[] args) throws Exception {
    Path root = args.length > 0 ? Paths.get(args[0]).toAbsolutePath().normalize() : Files.createTempDirectory("lrs-renderer-self-test-");
    Files.createDirectories(root);
    Path litematic = root.resolve("fixture.litematic");
    writeFixture(litematic);
    LitematicModel model = LitematicModel.read(litematic);
    if (model.blocks.size() != 5 || model.entities.size() != 1 || model.width() != 4 || model.height() != 2 || model.depth() != 3) throw new AssertionError("Litematic fixture parse failed");
    if (model.blockAt(40, 9, -30) == null || !"minecraft:repeater".equals(model.blockAt(40, 9, -30).name)) throw new AssertionError("Litematic storage order must be X fastest, Z second, Y vertical/slowest");
    if (model.blockAt(40, 9, -29) != null) throw new AssertionError("Y/Z axes were transposed while expanding BlockStates");
    if (!"minecraft:chest_minecart".equals(model.entities.get(0).name)) throw new AssertionError("Litematic region entity parse failed");
    if (model.entities.get(0).x != 41.5d || model.entities.get(0).y != 8d || model.entities.get(0).z != -28.5d) throw new AssertionError("Region-local entity position was not restored into block coordinates");
    verifyMachineGeometry();
    Path resourcePack = root.resolve("resource-pack");
    writeTexture(resourcePack.resolve("assets/minecraft/textures/block/stone.png"), new Color(38, 205, 92));
    writeModelFixtures(resourcePack);
    verifyResourceModels(resourcePack);
    Map<String, Object> job = job(litematic, root.resolve("png"), resourcePack);
    List<Path> outputs = VoxelRenderer.render(job, null, new VoxelRenderer.Progress() {
      public void report(String stage, String message, double fraction) {
      }
    });
    if (outputs.size() != 2) throw new AssertionError("Expected two PNG files");
    for (Path output : outputs) {
      BufferedImage image = ImageIO.read(output.toFile());
      if (image == null || image.getWidth() != 320 || image.getHeight() != 180) throw new AssertionError("Invalid PNG: " + output);
      if (!containsGreenStone(image)) throw new AssertionError("Resource-pack color was not applied: " + output);
      if (!containsTextureVariation(image)) throw new AssertionError("16x16 texture pixels were flattened: " + output);
    }
    System.out.println("WORKER_RENDER_SELF_TEST_OK");
    System.out.println("output=" + outputs.get(0));
  }

  static Map<String, Object> job(Path litematic, Path output) {
    return job(litematic, output, null);
  }

  static Map<String, Object> job(Path litematic, Path output, Path resourcePack) {
    Map<String, Object> root = new LinkedHashMap<String, Object>();
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("litematicPath", litematic.toString());
    root.put("source", source);
    Map<String, Object> resources = new LinkedHashMap<String, Object>();
    ArrayList<Object> packs = new ArrayList<Object>();
    if (resourcePack != null) packs.add(resourcePack.toString());
    resources.put("selectedResourcePacks", packs);
    root.put("resources", resources);
    Map<String, Object> camera = new LinkedHashMap<String, Object>();
    camera.put("projection", "orthographic");
    camera.put("baseAzimuthDeg", Double.valueOf(45d));
    camera.put("elevationDeg", Double.valueOf(35.264d));
    ArrayList<Object> offsets = new ArrayList<Object>();
    offsets.add(Long.valueOf(0));
    offsets.add(Long.valueOf(90));
    camera.put("selectedOffsetsDeg", offsets);
    root.put("camera", camera);
    Map<String, Object> outputMap = new LinkedHashMap<String, Object>();
    outputMap.put("directory", output.toString());
    outputMap.put("width", Long.valueOf(320));
    outputMap.put("height", Long.valueOf(180));
    outputMap.put("background", "transparent");
    outputMap.put("namingPrefix", "self-test");
    root.put("output", outputMap);
    Map<String, Object> execution = new LinkedHashMap<String, Object>();
    execution.put("lighting", "technical_fullbright");
    execution.put("directionSchedule", "parallel");
    root.put("execution", execution);
    return root;
  }

  static void writeFixture(Path file) throws Exception {
    OutputStream raw = Files.newOutputStream(file);
    DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw));
    try {
      out.writeByte(10);
      out.writeUTF("");
      tagHeader(out, 10, "Regions");
      tagHeader(out, 10, "Unnamed");
      coordinateCompound(out, "Position", new int[] { 40, 8, -30 });
      coordinateCompound(out, "Size", new int[] { 4, 2, 3 });
      out.writeByte(9);
      out.writeUTF("BlockStatePalette");
      out.writeByte(10);
      out.writeInt(6);
      paletteEntry(out, "minecraft:air");
      paletteEntry(out, "minecraft:stone");
      paletteEntry(out, "minecraft:redstone_wire");
      paletteEntry(out, "minecraft:piston", "facing", "east");
      paletteEntry(out, "minecraft:piston_head", "facing", "east");
      paletteEntry(out, "minecraft:repeater", "facing", "north");
      out.writeByte(12);
      out.writeUTF("BlockStates");
      int[] states = new int[24];
      states[8] = 1;
      states[1] = 2;
      states[2] = 3;
      states[3] = 4;
      states[12] = 5;
      long[] packed = pack(states, 3);
      out.writeInt(packed.length);
      for (long value : packed) out.writeLong(value);
      out.writeByte(9);
      out.writeUTF("Entities");
      out.writeByte(10);
      out.writeInt(1);
      out.writeByte(8);
      out.writeUTF("id");
      out.writeUTF("minecraft:chest_minecart");
      out.writeByte(9);
      out.writeUTF("Pos");
      out.writeByte(6);
      out.writeInt(3);
      out.writeDouble(1.5d);
      out.writeDouble(0d);
      out.writeDouble(1.5d);
      out.writeByte(9);
      out.writeUTF("Rotation");
      out.writeByte(5);
      out.writeInt(2);
      out.writeFloat(0f);
      out.writeFloat(0f);
      out.writeByte(0);
      out.writeByte(0);
      out.writeByte(0);
      out.writeByte(0);
    } finally {
      out.close();
    }
  }

  private static void writeTexture(Path target, Color color) throws Exception {
    Files.createDirectories(target.getParent());
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          int change = ((x / 2) + (y / 2)) % 2 == 0 ? 0 : 78;
          graphics.setColor(new Color(Math.max(0, color.getRed() - change), Math.max(0, color.getGreen() - change), Math.max(0, color.getBlue() - change)));
          graphics.fillRect(x, y, 1, 1);
        }
      }
    } finally {
      graphics.dispose();
    }
    ImageIO.write(image, "png", target.toFile());
  }

  private static void writeModelFixtures(Path root) throws Exception {
    writeText(root.resolve("assets/minecraft/blockstates/rail.json"), "{\"variants\":{\"shape=north_south\":{\"model\":\"minecraft:block/rail_ns\"},\"shape=north_east\":{\"model\":\"minecraft:block/rail_ne\"}}}");
    writeText(root.resolve("assets/minecraft/models/block/rail_ns.json"), plateModel("minecraft:block/rail"));
    writeText(root.resolve("assets/minecraft/models/block/rail_ne.json"), plateModel("minecraft:block/rail_corner"));
    writeTexture(root.resolve("assets/minecraft/textures/block/rail.png"), new Color(190, 160, 80));
    writeTexture(root.resolve("assets/minecraft/textures/block/rail_corner.png"), new Color(230, 180, 35));
    writeTexture(root.resolve("assets/minecraft/textures/block/powered_rail.png"), new Color(118, 92, 48));
    writeTexture(root.resolve("assets/minecraft/textures/block/powered_rail_on.png"), new Color(255, 194, 46));
    writeTexture(root.resolve("assets/minecraft/textures/item/diamond.png"), new Color(72, 236, 224));

    writeText(root.resolve("assets/minecraft/blockstates/oak_stairs.json"), "{\"variants\":{\"facing=north,half=bottom,shape=inner_left,waterlogged=false\":{\"model\":\"minecraft:block/test_inner_stairs\",\"uvlock\":true}}}");
    writeText(root.resolve("assets/minecraft/models/block/test_inner_stairs.json"), "{\"textures\":{\"all\":\"minecraft:block/stone\"},\"elements\":[" + cubeElement(0,0,0,16,8,16,"#all") + "," + cubeElement(0,8,8,16,16,16,"#all") + "," + cubeElement(0,8,0,8,16,8,"#all") + "]}");

    writeText(root.resolve("assets/minecraft/blockstates/white_carpet.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/test_carpet\"}}}");
    writeText(root.resolve("assets/minecraft/models/block/test_carpet.json"), "{\"textures\":{\"all\":\"minecraft:block/stone\"},\"elements\":[" + cubeElement(0,0,0,16,1,16,"#all") + "]}");

    writeText(root.resolve("assets/minecraft/blockstates/observer.json"), "{\"variants\":{\"facing=east,powered=false\":{\"model\":\"minecraft:block/test_observer\",\"y\":90}}}");
    writeText(root.resolve("assets/minecraft/models/block/test_observer.json"), directionalCube("observer_front", "observer_back", "observer_top", "observer_side"));
    for (String name : new String[] { "observer_front", "observer_back", "observer_top", "observer_side" }) writeTexture(root.resolve("assets/minecraft/textures/block/" + name + ".png"), new Color(115, 115, 115));

    writeText(root.resolve("assets/minecraft/blockstates/piston.json"), "{\"variants\":{\"extended=false,facing=west\":{\"model\":\"minecraft:block/test_piston\",\"y\":270}}}");
    writeText(root.resolve("assets/minecraft/models/block/test_piston.json"), directionalCube("piston_top", "piston_bottom", "piston_side", "piston_side"));
    for (String name : new String[] { "piston_top", "piston_bottom" }) writeTexture(root.resolve("assets/minecraft/textures/block/" + name + ".png"), new Color(150, 118, 75));
    writePistonSideTexture(root.resolve("assets/minecraft/textures/block/piston_side.png"));

    writeText(root.resolve("assets/minecraft/blockstates/redstone_wire.json"), "{\"multipart\":[{\"apply\":{\"model\":\"minecraft:block/test_dust_dot\"}},{\"when\":{\"north\":\"up\"},\"apply\":{\"model\":\"minecraft:block/test_dust_up\"}}]}");
    writeText(root.resolve("assets/minecraft/models/block/test_dust_dot.json"), "{\"textures\":{\"line\":\"minecraft:block/redstone_dust_dot\"},\"elements\":[{\"from\":[0,0.2,0],\"to\":[16,0.3,16],\"faces\":{\"up\":{\"texture\":\"#line\"}}}]}");
    writeText(root.resolve("assets/minecraft/models/block/test_dust_up.json"), "{\"textures\":{\"line\":\"minecraft:block/redstone_dust_line0\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,0.1],\"faces\":{\"north\":{\"texture\":\"#line\"}}}]}");
    writeTexture(root.resolve("assets/minecraft/textures/block/redstone_dust_dot.png"), new Color(210, 30, 20));
    writeTexture(root.resolve("assets/minecraft/textures/block/redstone_dust_line0.png"), new Color(210, 30, 20));
    writeChestAtlas(root.resolve("assets/minecraft/textures/entity/chest/normal.png"));
    writeDoubleChestAtlas(root.resolve("assets/minecraft/textures/entity/chest/normal_double.png"));
  }

  private static void verifyResourceModels(Path root) throws Exception {
    ArrayList<Path> packs = new ArrayList<Path>();
    packs.add(root);
    TextureResolver resolver = new TextureResolver(packs, null);
    try {
      MachineGeometry.Shape curve = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:rail", map("shape", "north_east")));
      MachineGeometry.FaceSpec curveFace = curve.parts.get(0).faceSpec(BlockFace.UP);
      TextureResolver.Texture curveTexture = curveFace == null ? null : resolver.textureResource(new LitematicModel.Block(0, 0, 0, "minecraft:rail", map("shape", "north_east")), curveFace);
      if (!MachineGeometry.RAIL_CURVED.equals(curve.parts.get(0).material) || curveFace == null || curveTexture == null || curveTexture.image == null || !curveTexture.resource.endsWith("/rail_corner.png") || resolver.rotation(new LitematicModel.Block(0, 0, 0, "minecraft:rail", map("shape", "north_east")), BlockFace.UP, MachineGeometry.RAIL_CURVED) != 0) throw new AssertionError("Curved rail state texture selection failed");

      Map<String, Object> poweredRailState = new HashMap<String, Object>();
      poweredRailState.put("shape", "north_south"); poweredRailState.put("powered", "true"); poweredRailState.put("waterlogged", "true");
      LitematicModel.Block poweredRail = new LitematicModel.Block(0, 0, 0, "minecraft:powered_rail", poweredRailState);
      MachineGeometry.Shape poweredRailShape = resolver.shape(null, poweredRail);
      TextureResolver.Texture poweredRailTexture = resolver.texture(poweredRail, BlockFace.UP, poweredRailShape.parts.get(0).material);
      if (poweredRailShape.parts.size() != 2 || !poweredRailTexture.resource.endsWith("/powered_rail_on.png")) throw new AssertionError("Waterlogged powered rail texture selection failed");

      Map<String, Object> stairState = new HashMap<String, Object>();
      stairState.put("facing", "north"); stairState.put("half", "bottom"); stairState.put("shape", "inner_left"); stairState.put("waterlogged", "false");
      MachineGeometry.Shape stairs = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:oak_stairs", stairState));
      if (stairs.parts.size() != 3) throw new AssertionError("Inner stair model state failed");

      MachineGeometry.Shape carpet = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:white_carpet", new HashMap<String, Object>()));
      if (carpet.parts.size() != 1 || Math.abs(carpet.parts.get(0).box.maxY - 0.0625d) > 0.00001d) throw new AssertionError("Carpet model height failed");

      Map<String, Object> observerState = new HashMap<String, Object>(); observerState.put("facing", "east"); observerState.put("powered", "false");
      MachineGeometry.Shape observer = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:observer", observerState));
      MachineGeometry.FaceSpec observerFront = observer.parts.get(0).faceSpec(BlockFace.EAST);
      if (observerFront == null || !observerFront.resource.endsWith("/observer_front.png")) throw new AssertionError("Observer X/Z model rotation failed");

      Map<String, Object> pistonState = new HashMap<String, Object>(); pistonState.put("facing", "west"); pistonState.put("extended", "false");
      MachineGeometry.Shape piston = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:piston", pistonState));
      MachineGeometry.FaceSpec pistonFront = piston.parts.get(0).faceSpec(BlockFace.WEST);
      if (pistonFront == null || !pistonFront.resource.endsWith("/piston_top.png")) throw new AssertionError("Piston west direct face mapping failed");

      Map<String, Object> dustState = new HashMap<String, Object>(); dustState.put("north", "up");
      MachineGeometry.Shape dust = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:redstone_wire", dustState));
      if (dust.parts.size() != 2 || !MachineGeometry.REDSTONE_VERTICAL.equals(dust.parts.get(1).material) || !dust.parts.get(1).shows(BlockFace.SOUTH) || dust.parts.get(1).box.minZ <= 0d) {
        throw new AssertionError("Redstone vertical state geometry failed");
      }

      LitematicModel.Block chest = new LitematicModel.Block(0, 0, 0, "minecraft:chest", map("facing", "north"));
      TextureResolver.Texture top = resolver.texture(chest, BlockFace.UP, MachineGeometry.CHEST_LID);
      TextureResolver.Texture front = resolver.texture(chest, BlockFace.NORTH, MachineGeometry.CHEST_LID);
      if (top.image == null || front.image == null || top.image.getWidth() != 14 || top.image.getHeight() != 14 || front.image.getHeight() != 5) {
        throw new AssertionError("Chest atlas face extraction failed");
      }
      Map<String, Object> largeChestState = new HashMap<String, Object>(); largeChestState.put("facing", "north"); largeChestState.put("type", "left");
      TextureResolver.Texture doubleTop = resolver.texture(new LitematicModel.Block(0, 0, 0, "minecraft:chest", largeChestState), BlockFace.UP, MachineGeometry.CHEST_DOUBLE_LID);
      if (doubleTop.image == null || !doubleTop.resource.contains("normal_double")) throw new AssertionError("Double chest atlas route failed");

      Color water = resolver.tint(new LitematicModel.Block(0, 0, 0, "minecraft:water", new HashMap<String, Object>()), MachineGeometry.FLUID_WATER, new Color(150, 150, 150, 255));
      if (water.getBlue() <= water.getRed() * 2 || water.getAlpha() > 120) throw new AssertionError("Water tint/transparency failed");

      Map<String, Object> signState = new HashMap<String, Object>(); signState.put("lrs_sign_text", "原理图\n测试");
      MachineGeometry.Shape sign = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:oak_sign", signState));
      MachineGeometry.FaceSpec signFace = null;
      for (MachineGeometry.Part part : sign.parts) if (MachineGeometry.SIGN_TEXT.equals(part.material)) {
        signFace = part.faceSpec(BlockFace.NORTH);
        if (signFace == null) signFace = part.faceSpec(BlockFace.SOUTH);
      }
      TextureResolver.Texture signText = signFace == null ? null : resolver.textureResource(new LitematicModel.Block(0, 0, 0, "minecraft:oak_sign", signState), signFace);
      if (signText == null || signText.image == null) throw new AssertionError("Sign block-entity text texture failed");

      Map<String, Object> frameState = new HashMap<String, Object>(); frameState.put("facing", "north"); frameState.put("lrs_entity", "true"); frameState.put("lrs_item_id", "minecraft:diamond"); frameState.put("lrs_item_rotation", "3");
      TextureResolver.Texture frameItem = resolver.texture(new LitematicModel.Block(0, 0, 0, "minecraft:item_frame", frameState), BlockFace.NORTH, MachineGeometry.FRAME_ITEM);
      if (frameItem.image == null || !frameItem.resource.endsWith("/item/diamond.png")) throw new AssertionError("Item frame NBT item texture failed");
    } finally {
      resolver.close();
    }

    double[] local = LitematicModel.normalizeEntityPosition(new double[] { 1.5d, 2d, 3.5d }, 40, 8, -30, 5, 5, 5);
    if (local[0] != 41.5d || local[1] != 10d || local[2] != -26.5d) throw new AssertionError("Region-relative entity coordinates failed");
    double[] absolute = LitematicModel.normalizeEntityPosition(new double[] { 41.5d, 10d, -26.5d }, 40, 8, -30, 5, 5, 5);
    if (absolute[0] != 81.5d || absolute[1] != 18d || absolute[2] != -56.5d) throw new AssertionError("Entity coordinates no longer follow the schematic region-local contract");
  }

  private static void assertCurveQuarter(String state, int quarter) {
    Map<String, Object> properties = new HashMap<String, Object>();
    properties.put("shape", state);
    MachineGeometry.Part part = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:rail", properties)).parts.get(0);
    MachineGeometry.FaceSpec spec = part.faceSpec(BlockFace.UP);
    double expectedU = quarter == 0 ? 0d : quarter == 1 ? 0d : quarter == 2 ? 1d : 1d;
    double expectedV = quarter == 0 ? 0d : quarter == 1 ? 1d : quarter == 2 ? 1d : 0d;
    if (!MachineGeometry.RAIL_CURVED.equals(part.material) || spec == null || spec.uv[0] != expectedU || spec.uv[1] != expectedV) {
      throw new AssertionError("Curved rail direction mapping failed for " + state);
    }
  }

  private static String plateModel(String texture) {
    return "{\"textures\":{\"rail\":\"" + texture + "\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,1,16],\"faces\":{\"up\":{\"texture\":\"#rail\"}}}]}";
  }

  private static String directionalCube(String front, String back, String top, String side) {
    return "{\"textures\":{\"front\":\"minecraft:block/" + front + "\",\"back\":\"minecraft:block/" + back + "\",\"top\":\"minecraft:block/" + top + "\",\"side\":\"minecraft:block/" + side + "\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"north\":{\"texture\":\"#front\"},\"south\":{\"texture\":\"#back\"},\"up\":{\"texture\":\"#top\"},\"down\":{\"texture\":\"#top\"},\"east\":{\"texture\":\"#side\"},\"west\":{\"texture\":\"#side\"}}}]}";
  }

  private static String cubeElement(int x1, int y1, int z1, int x2, int y2, int z2, String texture) {
    return "{\"from\":[" + x1 + "," + y1 + "," + z1 + "],\"to\":[" + x2 + "," + y2 + "," + z2 + "],\"faces\":{\"up\":{\"texture\":\"" + texture + "\"},\"down\":{\"texture\":\"" + texture + "\"},\"north\":{\"texture\":\"" + texture + "\"},\"south\":{\"texture\":\"" + texture + "\"},\"east\":{\"texture\":\"" + texture + "\"},\"west\":{\"texture\":\"" + texture + "\"}}}";
  }

  private static void writeChestAtlas(Path target) throws Exception {
    Files.createDirectories(target.getParent());
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(104, 64, 28)); graphics.fillRect(0, 0, 64, 64);
      graphics.setColor(new Color(224, 174, 72)); graphics.fillRect(14, 0, 14, 14);
      graphics.setColor(new Color(173, 112, 43)); graphics.fillRect(14, 14, 14, 5);
      graphics.setColor(new Color(126, 72, 28)); graphics.fillRect(14, 33, 14, 10);
    } finally {
      graphics.dispose();
    }
    ImageIO.write(image, "png", target.toFile());
  }

  private static void writeDoubleChestAtlas(Path target) throws Exception {
    Files.createDirectories(target.getParent());
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(92, 54, 22)); graphics.fillRect(0, 0, 64, 64);
      graphics.setColor(new Color(229, 181, 74)); graphics.fillRect(14, 0, 30, 14);
      graphics.setColor(new Color(182, 117, 43)); graphics.fillRect(14, 14, 30, 5);
      graphics.setColor(new Color(129, 74, 27)); graphics.fillRect(14, 33, 30, 10);
    } finally {
      graphics.dispose();
    }
    ImageIO.write(image, "png", target.toFile());
  }

  private static void writePistonSideTexture(Path target) throws Exception {
    Files.createDirectories(target.getParent());
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(96, 101, 104)); graphics.fillRect(0, 0, 16, 16);
      graphics.setColor(new Color(220, 174, 72)); graphics.fillRect(0, 0, 16, 4);
      graphics.setColor(new Color(58, 61, 63)); graphics.fillRect(0, 14, 16, 2);
    } finally {
      graphics.dispose();
    }
    ImageIO.write(image, "png", target.toFile());
  }

  private static void writeText(Path target, String value) throws Exception {
    Files.createDirectories(target.getParent());
    Files.write(target, value.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean containsGreenStone(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int argb = image.getRGB(x, y);
        int alpha = (argb >>> 24) & 255;
        int red = (argb >>> 16) & 255;
        int green = (argb >>> 8) & 255;
        int blue = argb & 255;
        if (alpha > 220 && green > red * 2 && green > blue * 2) return true;
      }
    }
    return false;
  }

  private static boolean containsTextureVariation(BufferedImage image) {
    Set<Integer> colors = new HashSet<Integer>();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int argb = image.getRGB(x, y);
        int alpha = (argb >>> 24) & 255;
        int red = (argb >>> 16) & 255;
        int green = (argb >>> 8) & 255;
        int blue = argb & 255;
        if (alpha > 220 && green > red * 2 && green > blue * 2) colors.add(Integer.valueOf(argb));
      }
    }
    return colors.size() >= 3;
  }

  private static void verifyMachineGeometry() {
    ArrayList<LitematicModel.Block> framing = new ArrayList<LitematicModel.Block>();
    for (int index = 0; index < 128; index++) framing.add(new LitematicModel.Block(index, 0, 0, "minecraft:stone", new HashMap<String, Object>()));
    for (int index = 0; index < 4; index++) framing.add(new LitematicModel.Block(1000 + index, 0, 0, "minecraft:stone", new HashMap<String, Object>()));
    LitematicModel framed = LitematicModel.of(framing);
    if (framed.framingBlocks.size() != 128 || framed.omittedFramingBlocks() != 4) throw new AssertionError("Auto framing must ignore detached tiny block groups");

    Map<String, Object> piston = new HashMap<String, Object>();
    piston.put("facing", "north");
    LitematicModel.Block head = new LitematicModel.Block(0, 0, 0, "minecraft:piston_head", piston);
    if (MachineGeometry.shape(head).boxes.size() != 2) throw new AssertionError("Piston head geometry missing");
    if (MachineGeometry.occludes(head)) throw new AssertionError("Piston head must preserve its empty volume");
    LitematicModel.Block wire = new LitematicModel.Block(0, 0, 0, "minecraft:redstone_wire", new HashMap<String, Object>());
    if (MachineGeometry.occludes(wire)) throw new AssertionError("Redstone dust must not occlude the supporting block");
    LitematicModel.Block base = new LitematicModel.Block(0, 0, 0, "minecraft:piston", piston);
    if (!MachineGeometry.occludes(base)) throw new AssertionError("Piston base must render as a full block");

    Map<String, Object> wireState = new HashMap<String, Object>();
    wireState.put("north", "up");
    wireState.put("east", "side");
    wireState.put("south", "none");
    wireState.put("west", "none");
    wireState.put("power", "15");
    MachineGeometry.Shape wireShape = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:redstone_wire", wireState));
    if (wireShape.parts.size() != 2 || !MachineGeometry.REDSTONE_TOP.equals(wireShape.parts.get(0).material)
      || !MachineGeometry.REDSTONE_VERTICAL.equals(wireShape.parts.get(1).material) || !wireShape.parts.get(1).shows(BlockFace.SOUTH)
      || wireShape.parts.get(1).box.minZ <= 0d || wireShape.parts.get(1).box.maxY <= 1d) {
      throw new AssertionError("Redstone side/up connection geometry missing");
    }

    Map<String, Object> extended = new HashMap<String, Object>();
    extended.put("facing", "east");
    extended.put("extended", "true");
    MachineGeometry.Shape extendedPiston = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:piston", extended));
    if (extendedPiston.parts.size() != 2 || extendedPiston.parts.get(0).box.maxX != 0.75d || extendedPiston.parts.get(1).box.minX != 0.75d) {
      throw new AssertionError("Extended east piston body/rod alignment failed");
    }

    for (BlockFace facing : BlockFace.values()) {
      Map<String, Object> state = new HashMap<String, Object>();
      state.put("facing", facing.id);
      state.put("extended", "false");
      MachineGeometry.Shape unextended = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:piston", state));
      if (!unextended.fullCube || !unextended.occluding) throw new AssertionError("Unextended piston must remain a solid full cube");
      for (BlockFace face : BlockFace.values()) {
        MachineGeometry.FaceSpec spec = unextended.parts.get(0).faceSpec(face);
        if (spec == null) throw new AssertionError("Dedicated piston face mapping missing for " + facing + "/" + face);
        if (face == facing && !spec.resource.endsWith("/piston_top.png")) throw new AssertionError("Piston head face mapping failed for " + facing);
        if (face == facing.opposite() && !spec.resource.endsWith("/piston_bottom.png")) throw new AssertionError("Piston rear face mapping failed for " + facing);
        if (face != facing && face != facing.opposite() && (!spec.resource.endsWith("/piston_side.png") || MachineGeometry.pistonSideHeadwardDirection(face, spec.uv) != facing)) {
          throw new AssertionError("Piston side yellow strip must point to the head for " + facing + "/" + face);
        }
      }
    }

    verifyPistonFamily("minecraft:sticky_piston", map("extended", "true"));
    verifyPistonFamily("minecraft:piston_head", map("type", "sticky"));
    verifyPistonFamily("minecraft:moving_piston", map("type", "normal"));

    Map<String, Object> repeater = new HashMap<String, Object>();
    repeater.put("facing", "north");
    repeater.put("delay", "4");
    repeater.put("powered", "true");
    repeater.put("locked", "true");
    MachineGeometry.Shape repeaterShape = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:repeater", repeater));
    if (repeaterShape.parts.size() != 6 || repeaterShape.parts.get(4).box.centerZ() < 0.87d || !MachineGeometry.TORCH_HEAD_ON.equals(repeaterShape.parts.get(4).material)) {
      throw new AssertionError("Repeater delay/powered/locked state geometry failed");
    }

    Map<String, Object> compare = new HashMap<String, Object>();
    compare.put("facing", "north");
    compare.put("powered", "true");
    compare.put("mode", "compare");
    Map<String, Object> subtract = new HashMap<String, Object>(compare);
    subtract.put("mode", "subtract");
    MachineGeometry.Shape compareShape = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:comparator", compare));
    MachineGeometry.Shape subtractShape = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:comparator", subtract));
    if (subtractShape.parts.get(6).box.maxY <= compareShape.parts.get(6).box.maxY || subtractShape.parts.get(6).box.centerZ() == compareShape.parts.get(6).box.centerZ()) {
      throw new AssertionError("Comparator mode geometry failed");
    }

    Map<String, Object> hopperState = new HashMap<String, Object>();
    hopperState.put("facing", "east");
    MachineGeometry.Shape hopper = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:hopper", hopperState));
    boolean inside = false;
    boolean eastSpout = false;
    for (MachineGeometry.Part part : hopper.parts) {
      if (MachineGeometry.HOPPER_INSIDE.equals(part.material)) inside = true;
      if (MachineGeometry.HOPPER_OUTSIDE.equals(part.material) && part.box.minX == 0.5d && part.box.maxX == 1d && part.box.maxY == 0.375d) eastSpout = true;
    }
    if (!inside || !eastSpout || MachineGeometry.occludes(new LitematicModel.Block(0, 0, 0, "minecraft:hopper", hopperState))) {
      throw new AssertionError("Opaque hopper bowl/inside/spout geometry failed");
    }

    Map<String, Object> ascendingRail = new HashMap<String, Object>();
    ascendingRail.put("shape", "ascending_north");
    MachineGeometry.Part railPart = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:rail", ascendingRail)).parts.get(0);
    if (!MachineGeometry.RAIL_ASCENDING.equals(railPart.material) || railPart.slopeUp != BlockFace.NORTH) {
      throw new AssertionError("Ascending rail geometry missing");
    }
    assertCurveQuarter("north_east", 3);
    assertCurveQuarter("south_east", 0);
    assertCurveQuarter("south_west", 1);
    assertCurveQuarter("north_west", 2);

    Map<String, Object> water = new HashMap<String, Object>();
    water.put("level", "5");
    MachineGeometry.Shape waterShape = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:water", water));
    if (!MachineGeometry.FLUID_WATER_FLOW.equals(waterShape.parts.get(0).material) || waterShape.parts.get(0).box.maxY >= 0.5d) {
      throw new AssertionError("Flowing water height geometry missing");
    }
    Map<String, Object> waterlogged = new HashMap<String, Object>();
    waterlogged.put("waterlogged", "true");
    MachineGeometry.Shape waterloggedRail = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:rail", waterlogged));
    if (waterloggedRail.parts.size() != 2 || !MachineGeometry.FLUID_WATER.equals(waterloggedRail.parts.get(1).material)) {
      throw new AssertionError("Waterlogged block fluid overlay missing");
    }

    Map<String, Object> leftChestState = new HashMap<String, Object>(); leftChestState.put("facing", "north"); leftChestState.put("type", "left");
    Map<String, Object> rightChestState = new HashMap<String, Object>(); rightChestState.put("facing", "north"); rightChestState.put("type", "right");
    LitematicModel.Block leftChest = new LitematicModel.Block(0, 0, 0, "minecraft:chest", leftChestState);
    LitematicModel.Block rightChest = new LitematicModel.Block(1, 0, 0, "minecraft:chest", rightChestState);
    ArrayList<LitematicModel.Block> chestBlocks = new ArrayList<LitematicModel.Block>(); chestBlocks.add(leftChest); chestBlocks.add(rightChest);
    LitematicModel chestModel = LitematicModel.of(chestBlocks);
    MachineGeometry.Shape leftChestShape = MachineGeometry.shape(chestModel, leftChest);
    MachineGeometry.Shape rightChestShape = MachineGeometry.shape(chestModel, rightChest);
    if (!MachineGeometry.CHEST_DOUBLE_BOTTOM.equals(leftChestShape.parts.get(0).material) || !MachineGeometry.CHEST_DOUBLE_BOTTOM.equals(rightChestShape.parts.get(0).material)
      || leftChestShape.parts.get(0).box.maxX != 1d || rightChestShape.parts.get(0).box.minX != 0d || leftChestShape.parts.size() != 3 || rightChestShape.parts.size() != 2) {
      throw new AssertionError("Adjacent chest pair geometry/latch failed");
    }

    Map<String, Object> signState = new HashMap<String, Object>();
    signState.put("rotation", "4"); signState.put("lrs_sign_text", "DsLR\n状态");
    MachineGeometry.Shape sign = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:oak_hanging_sign", signState));
    boolean board = false, text = false;
    for (MachineGeometry.Part part : sign.parts) {
      board |= MachineGeometry.SIGN_BOARD.equals(part.material);
      text |= MachineGeometry.SIGN_TEXT.equals(part.material);
    }
    if (!board || !text) throw new AssertionError("Sign board/text geometry missing");

    Map<String, Object> frameState = new HashMap<String, Object>();
    frameState.put("facing", "north"); frameState.put("lrs_entity", "true"); frameState.put("lrs_item_id", "minecraft:diamond"); frameState.put("lrs_item_rotation", "3");
    MachineGeometry.Shape frame = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, "minecraft:item_frame", frameState));
    boolean frameBoard = false, frameItem = false;
    for (MachineGeometry.Part part : frame.parts) {
      frameBoard |= MachineGeometry.ITEM_FRAME.equals(part.material);
      frameItem |= MachineGeometry.FRAME_ITEM.equals(part.material);
    }
    if (!frameBoard || !frameItem) throw new AssertionError("Item frame/entity item geometry missing");
    MachineGeometry.Box frameBox = frame.parts.get(0).box;
    if (frameBox.minZ < 0.45d || frameBox.maxZ > 0.55d) throw new AssertionError("Item frame geometry is not centered on entity position");

    LitematicModel.Entity minecartEntity = new LitematicModel.Entity(0.5d, 0d, 0.5d, 0f, "minecraft:chest_minecart", new HashMap<String, Object>());
    MachineGeometry.Shape minecart = MachineGeometry.shape(minecartEntity.renderBlock());
    if (minecart.parts.size() < 10 || !MachineGeometry.MINECART.equals(minecart.parts.get(0).material)) {
      throw new AssertionError("Minecart entity geometry missing");
    }
    Map<String, Object> frameData = new HashMap<String, Object>(); frameData.put("ItemRotation", Integer.valueOf(3));
    Map<String, Object> framedItem = new HashMap<String, Object>(); framedItem.put("id", "minecraft:diamond"); frameData.put("Item", framedItem);
    LitematicModel.Block entityBlock = new LitematicModel.Entity(12.5d, 4d, -7.5d, 0f, "minecraft:item_frame", frameData).renderBlock();
    if (entityBlock.properties.containsKey("lrs_entity_anchor") || !"minecraft:diamond".equals(entityBlock.properties.get("lrs_item_id")) || !Integer.valueOf(3).equals(entityBlock.properties.get("lrs_item_rotation"))) {
      throw new AssertionError("Entity placement baseline or item-frame NBT route failed");
    }

    TextureResolver resolver = new TextureResolver(new ArrayList<Path>(), null);
    try {
      if (resolver.rotation(new LitematicModel.Block(0, 0, 0, "minecraft:repeater", map("facing", "east")), BlockFace.UP, MachineGeometry.REPEATER_BASE) != 1) {
        throw new AssertionError("Repeater top texture rotation failed");
      }
      if (resolver.rotation(new LitematicModel.Block(0, 0, 0, "minecraft:chest", map("facing", "east")), BlockFace.UP, MachineGeometry.CHEST_LID) != 1) {
        throw new AssertionError("Chest top texture rotation failed");
      }
      Color vineTint = resolver.tint(new LitematicModel.Block(0, 0, 0, "minecraft:vine", new HashMap<String, Object>()), MachineGeometry.BLOCK, new Color(180, 180, 180, 255), 0);
      if (vineTint.getGreen() <= vineTint.getRed() || vineTint.getGreen() <= vineTint.getBlue()) throw new AssertionError("Vine tint must be green");
      MachineGeometry.Shape directPiston = resolver.shape(null, new LitematicModel.Block(0, 0, 0, "minecraft:piston", map("facing", "north")));
      MachineGeometry.FaceSpec directSide = directPiston.parts.get(0).faceSpec(BlockFace.EAST);
      if (directSide == null || !directSide.resource.endsWith("/piston_side.png") || MachineGeometry.pistonSideHeadwardDirection(BlockFace.EAST, directSide.uv) != BlockFace.NORTH
        || resolver.rotation(new LitematicModel.Block(0, 0, 0, "minecraft:piston", map("facing", "north")), BlockFace.EAST, MachineGeometry.PISTON_BODY) != 0) {
        throw new AssertionError("Dedicated piston side texture must bypass generic model rotation");
      }
      if (resolver.rotation(new LitematicModel.Block(0, 0, 0, "minecraft:observer", map("facing", "east")), BlockFace.NORTH, MachineGeometry.BLOCK) == 0) {
        throw new AssertionError("Observer side texture orientation failed");
      }
    } finally {
      resolver.close();
    }
  }

  private static void verifyPistonFamily(String name, Map<String, Object> seed) {
    for (BlockFace facing : BlockFace.values()) {
      Map<String, Object> state = new HashMap<String, Object>(seed);
      state.put("facing", facing.id);
      MachineGeometry.Shape shape = MachineGeometry.shape(new LitematicModel.Block(0, 0, 0, name, state));
      if (shape.parts.size() < 2) throw new AssertionError("Piston family geometry missing for " + name + "/" + facing);
      for (MachineGeometry.Part part : shape.parts) {
        for (BlockFace face : BlockFace.values()) {
          MachineGeometry.FaceSpec spec = part.faceSpec(face);
          if (spec == null || !spec.resource.endsWith("/piston_side.png")) continue;
          if (MachineGeometry.pistonSideHeadwardDirection(face, spec.uv) != facing) {
            throw new AssertionError("Piston family side direction failed for " + name + "/" + facing + "/" + face);
          }
        }
      }
    }
  }

  private static Map<String, Object> map(String key, String value) {
    Map<String, Object> result = new HashMap<String, Object>();
    result.put(key, value);
    return result;
  }

  private static void tagHeader(DataOutputStream out, int type, String name) throws Exception {
    out.writeByte(type);
    out.writeUTF(name);
  }

  private static void intArray(DataOutputStream out, String name, int[] values) throws Exception {
    out.writeByte(11);
    out.writeUTF(name);
    out.writeInt(values.length);
    for (int value : values) out.writeInt(value);
  }

  private static void coordinateCompound(DataOutputStream out, String name, int[] values) throws Exception {
    out.writeByte(10);
    out.writeUTF(name);
    out.writeByte(3);
    out.writeUTF("x");
    out.writeInt(values[0]);
    out.writeByte(3);
    out.writeUTF("y");
    out.writeInt(values[1]);
    out.writeByte(3);
    out.writeUTF("z");
    out.writeInt(values[2]);
    out.writeByte(0);
  }

  private static void paletteEntry(DataOutputStream out, String name) throws Exception {
    paletteEntry(out, name, "", "");
  }

  private static void paletteEntry(DataOutputStream out, String name, String property, String value) throws Exception {
    out.writeByte(8);
    out.writeUTF("Name");
    out.writeUTF(name);
    if (!property.isEmpty()) {
      out.writeByte(10);
      out.writeUTF("Properties");
      out.writeByte(8);
      out.writeUTF(property);
      out.writeUTF(value);
      out.writeByte(0);
    }
    out.writeByte(0);
  }

  private static long[] pack(int[] values, int bits) {
    long[] result = new long[(values.length * bits + 63) / 64];
    long mask = (1L << bits) - 1L;
    for (int index = 0; index < values.length; index++) {
      long value = values[index] & mask;
      int bitIndex = index * bits;
      int longIndex = bitIndex >>> 6;
      int offset = bitIndex & 63;
      result[longIndex] |= value << offset;
      if (offset + bits > 64) result[longIndex + 1] |= value >>> (64 - offset);
    }
    return result;
  }
}

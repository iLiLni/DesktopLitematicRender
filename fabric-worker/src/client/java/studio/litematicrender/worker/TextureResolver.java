package studio.litematicrender.worker;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

final class TextureResolver implements AutoCloseable {
  static final class Texture {
    final BufferedImage image;
    final Color fallback;
    final String resource;
    final boolean resolved;

    Texture(BufferedImage image, Color fallback, String resource, boolean resolved) {
      this.image = image;
      this.fallback = fallback;
      this.resource = resource;
      this.resolved = resolved;
    }
  }

  private final List<Path> packs = new ArrayList<Path>();
  private final Path clientJar;
  private final BlockModelResolver models;
  private final Map<String, Texture> textures = new HashMap<String, Texture>();
  private final Map<Path, ZipFile> archives = new HashMap<Path, ZipFile>();
  private int resolved;
  private int fallback;

  TextureResolver(List<Path> resourcePacks, Path clientJar) {
    if (resourcePacks != null) {
      for (Path pack : resourcePacks) if (pack != null && Files.exists(pack)) packs.add(pack.toAbsolutePath().normalize());
    }
    this.clientJar = clientJar != null && Files.isRegularFile(clientJar) ? clientJar.toAbsolutePath().normalize() : null;
    this.models = new BlockModelResolver(this);
  }

  MachineGeometry.Shape shape(LitematicModel model, LitematicModel.Block block) {
    String name = localName(block.name);
    boolean entity = truth(block.properties, "lrs_entity");
    boolean special = entity || "water".equals(name) || "lava".equals(name) || name.endsWith("chest")
      || name.endsWith("_banner") || name.endsWith("_wall_banner")
      || name.endsWith("_sign") || "sign".equals(name)
      || "redstone_wire".equals(name) || name.endsWith("_rail") || "rail".equals(name)
      || "piston".equals(name) || "sticky_piston".equals(name) || "piston_head".equals(name) || "moving_piston".equals(name);
    MachineGeometry.Shape result = special ? null : models.resolve(block);
    if (result == null) return MachineGeometry.shape(model, block);
    return truth(block.properties, "waterlogged") && !result.fullCube ? MachineGeometry.withWaterlogged(result) : result;
  }

  boolean occludes(LitematicModel model, LitematicModel.Block block) {
    if (block == null) return false;
    MachineGeometry.Shape value = shape(model, block);
    return value.fullCube && value.occluding;
  }

  Texture texture(LitematicModel.Block block, BlockFace face) {
    return texture(block, face, MachineGeometry.BLOCK);
  }

  Texture texture(LitematicModel.Block block, BlockFace face, String material) {
    String key = stateKey(block) + "|" + material + "|" + face.id;
    Texture cached = textures.get(key);
    if (cached != null) return cached;
    if (MachineGeometry.REDSTONE_TOP.equals(material)) {
      Texture wire = redstoneTexture(block);
      textures.put(key, wire);
      if (wire.resolved) resolved++; else fallback++;
      return wire;
    }
    if (MachineGeometry.REDSTONE_VERTICAL.equals(material)) {
      Texture wire = verticalRedstoneTexture(block);
      textures.put(key, wire);
      if (wire.resolved) resolved++; else fallback++;
      return wire;
    }
    if (MachineGeometry.TORCH_STEM.equals(material) || MachineGeometry.TORCH_HEAD_ON.equals(material) || MachineGeometry.TORCH_HEAD_OFF.equals(material)) {
      Texture torch = torchTexture(block, material);
      textures.put(key, torch);
      if (torch.resolved) resolved++; else fallback++;
      return torch;
    }
    if (isChestMaterial(material)) {
      Texture chest = chestTexture(block, face, material);
      textures.put(key, chest);
      if (chest.resolved) resolved++; else fallback++;
      return chest;
    }
    List<String> candidates = textureCandidates(block, face, material);
    for (String resource : candidates) {
      BufferedImage image = image(resource);
      if (image != null) {
        BufferedImage prepared = prepare(image, material);
        Texture value = new Texture(prepared, average(prepared), resource, true);
        textures.put(key, value);
        resolved++;
        return value;
      }
    }
    Texture value = new Texture(null, fallback(block.name), candidates.isEmpty() ? "" : candidates.get(0), false);
    textures.put(key, value);
    fallback++;
    return value;
  }

  Texture textureResource(LitematicModel.Block block, MachineGeometry.FaceSpec face) {
    String key = stateKey(block) + "|model|" + face.resource;
    Texture cached = textures.get(key);
    if (cached != null) return cached;
    BufferedImage image = "lrs:generated/sign_text".equals(face.resource) ? signTextTexture(block) : image(face.resource);
    Texture value = image == null
      ? new Texture(null, fallback(block.name), face.resource, false)
      : new Texture(image, average(image), face.resource, true);
    textures.put(key, value);
    if (value.resolved) resolved++; else fallback++;
    return value;
  }

  Color tint(LitematicModel.Block block, Color color) {
    return tint(block, MachineGeometry.BLOCK, color);
  }

  Color tint(LitematicModel.Block block, String material, Color color) {
    return tint(block, material, color, -1);
  }

  Color tint(LitematicModel.Block block, String material, Color color, int tintIndex) {
    if ("minecraft:redstone_wire".equals(block.name)) {
      int power = number(block.properties.get("power"));
      float amount = Math.max(0f, Math.min(1f, power / 15f));
      Color target = mix(new Color(76, 10, 10, color.getAlpha()), new Color(255, 50, 34, color.getAlpha()), amount);
      float detail = 0.50f + 0.50f * Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue())) / 255f;
      return new Color(Math.min(255, Math.round(target.getRed() * detail)), Math.min(255, Math.round(target.getGreen() * detail)), Math.min(255, Math.round(target.getBlue() * detail)), color.getAlpha());
    }
    String name = localName(block.name);
    if (MachineGeometry.FLUID_WATER.equals(material) || MachineGeometry.FLUID_WATER_FLOW.equals(material) || "water".equals(name)) {
      double detail = 0.42d + 0.72d * luminance(color) / 255d;
      return new Color(clampColor(63 * detail), clampColor(118 * detail), clampColor(228 * detail), Math.min(color.getAlpha(), 112));
    }
    if (MachineGeometry.FLUID_LAVA.equals(material) || MachineGeometry.FLUID_LAVA_FLOW.equals(material) || "lava".equals(name)) {
      if (saturation(color) < 36) {
        double detail = 0.48d + 0.72d * luminance(color) / 255d;
        return new Color(clampColor(255 * detail), clampColor(93 * detail), clampColor(18 * detail), 245);
      }
      return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(250, Math.max(205, color.getAlpha())));
    }
    if (name.contains("glass")) return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(color.getAlpha(), name.contains("stained") ? 106 : 82));
    if (name.contains("slime")) return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(color.getAlpha(), 122));
    if (name.contains("honey")) return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(color.getAlpha(), 138));
    if (name.contains("ice")) return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(color.getAlpha(), 148));
    if (tintIndex >= 0 && (name.contains("grass") || name.contains("fern"))) return multiply(color, new Color(109, 162, 66, color.getAlpha()));
    if (tintIndex >= 0 && name.contains("leaves")) return multiply(color, new Color(86, 142, 64, color.getAlpha()));
    if (tintIndex >= 0 && ("vine".equals(name) || "cave_vines".equals(name))) return multiply(color, new Color(84, 158, 61, color.getAlpha()));
    return color;
  }

  String report() {
    return "材质贴图：已解析 " + resolved + " 类状态方块面，回退 " + fallback + " 类状态方块面；" + models.report()
      + "；资源包优先级=" + (packs.isEmpty() ? "原版" : "所选资源包→原版");
  }

  private String stateKey(LitematicModel.Block block) {
    StringBuilder result = new StringBuilder(block.name == null ? "minecraft:stone" : block.name);
    ArrayList<String> keys = new ArrayList<String>(block.properties.keySet());
    Collections.sort(keys);
    for (String key : keys) result.append('|').append(key).append('=').append(block.properties.get(key));
    return result.toString();
  }

  private List<String> textureCandidates(LitematicModel.Block block, BlockFace face, String material) {
    String id = block.name == null ? "minecraft:stone" : block.name;
    String namespace = "minecraft";
    String name = id;
    int colon = id.indexOf(':');
    if (colon >= 0) {
      namespace = id.substring(0, colon);
      name = id.substring(colon + 1);
    }
    name = name.toLowerCase(Locale.ROOT);
    ArrayList<String> names = new ArrayList<String>();
    BlockFace facing = BlockFace.fromProperty(block.properties.get("facing"), BlockFace.NORTH);
    String axis = property(block.properties, "axis");
    if (MachineGeometry.REDSTONE_VERTICAL.equals(material)) {
      names.add("redstone_dust_line0");
      names.add("redstone_dust_dot");
    } else if (MachineGeometry.RAIL_FLAT.equals(material) || MachineGeometry.RAIL_ASCENDING.equals(material)) {
      railTextureCandidates(names, name, truth(block.properties, "powered"));
    } else if (MachineGeometry.RAIL_CURVED.equals(material)) {
      if ("rail".equals(name)) names.add("rail_corner");
      railTextureCandidates(names, name, truth(block.properties, "powered"));
      names.add("rail_curved");
      names.add("rail");
    } else if (MachineGeometry.FLUID_WATER.equals(material)) {
      names.add("water_still");
      names.add("water_flow");
    } else if (MachineGeometry.FLUID_WATER_FLOW.equals(material)) {
      names.add("water_flow");
      names.add("water_still");
    } else if (MachineGeometry.FLUID_LAVA.equals(material)) {
      names.add("lava_still");
      names.add("lava_flow");
    } else if (MachineGeometry.FLUID_LAVA_FLOW.equals(material)) {
      names.add("lava_flow");
      names.add("lava_still");
    } else if (isChestMaterial(material)) {
      if (name.contains("ender")) names.add("entity/chest/ender");
      else if (name.contains("trapped")) names.add("entity/chest/trapped");
      else names.add("entity/chest/normal");
      names.add("chest_side");
      names.add("chest_front");
    } else if (MachineGeometry.CHEST_LATCH.equals(material)) {
      names.add("gold_block");
      names.add("entity/chest/normal");
    } else if (MachineGeometry.MINECART.equals(material)) {
      names.add("iron_block");
      names.add("entity/minecart");
    } else if (MachineGeometry.MINECART_WHEEL.equals(material)) {
      names.add("blackstone");
      names.add("deepslate");
    } else if (MachineGeometry.MINECART_CARGO.equals(material)) {
      if (name.contains("tnt")) names.add("tnt_side");
      else if (name.contains("furnace")) names.add("furnace_side");
      else if (name.contains("command")) names.add("command_block_side");
      else if (name.contains("spawner")) names.add("spawner");
      else names.add("oak_planks");
    } else if (MachineGeometry.ITEM_FRAME.equals(material)) {
      if (name.contains("glow_item_frame")) names.add("entity/glow_item_frame");
      names.add("entity/item_frame");
      names.add("oak_planks");
    } else if (MachineGeometry.FRAME_ITEM.equals(material)) {
      addItemTextureCandidates(names, property(block.properties, "lrs_item_id"));
      names.add("item/item_frame");
    } else if (MachineGeometry.ITEM_ENTITY.equals(material)) {
      addItemTextureCandidates(names, property(block.properties, "lrs_item_id"));
      names.add("item/item_frame");
    } else if (MachineGeometry.SIGN_BOARD.equals(material) || MachineGeometry.SIGN_POST.equals(material)) {
      String wood = signWood(name);
      names.add(wood + "_planks");
      names.add("oak_planks");
    } else if (MachineGeometry.ENTITY.equals(material)) {
      if (name.contains("boat")) names.add("oak_planks");
      else if (name.contains("armor_stand")) names.add("oak_planks");
      else if (name.contains("item_frame")) {
        if (name.contains("glow")) names.add("entity/glow_item_frame");
        names.add("entity/item_frame");
        names.add("oak_planks");
      }
      else if ("item".equals(name)) {
        addItemTextureCandidates(names, property(block.properties, "lrs_item_id"));
        names.add("item/item_frame");
      }
      else names.add("stone");
    } else if (MachineGeometry.REPEATER_BASE.equals(material)) {
      if (face == BlockFace.UP) names.add(truth(block.properties, "powered") ? "repeater_on" : "repeater");
      else names.add("smooth_stone_slab_side");
    } else if (MachineGeometry.COMPARATOR_BASE.equals(material)) {
      if (face == BlockFace.UP) names.add(truth(block.properties, "powered") ? "comparator_on" : "comparator");
      else names.add("smooth_stone_slab_side");
    } else if (MachineGeometry.TORCH_ON.equals(material)) {
      names.add("redstone_torch");
      names.add("torch");
    } else if (MachineGeometry.TORCH_OFF.equals(material)) {
      names.add("redstone_torch_off");
      names.add("redstone_torch");
    } else if (MachineGeometry.REPEATER_LOCK.equals(material)) {
      names.add("repeater");
      names.add("smooth_stone");
    } else if (MachineGeometry.HOPPER_INSIDE.equals(material)) {
      names.add("hopper_inside");
      names.add("hopper_outside");
    } else if (MachineGeometry.HOPPER_OUTSIDE.equals(material)) {
      names.add("hopper_outside");
    } else if (MachineGeometry.PISTON_BODY.equals(material)) {
      if (face == facing) names.add("piston_inner");
      else if (face == facing.opposite()) names.add("piston_bottom");
      else names.add("piston_side");
    } else if (MachineGeometry.PISTON_HEAD.equals(material)) {
      boolean sticky = "sticky".equals(property(block.properties, "type"));
      if (face == facing) names.add(sticky ? "piston_top_sticky" : "piston_top");
      else if (face == facing.opposite()) names.add("piston_inner");
      else names.add("piston_side");
    } else if (MachineGeometry.PISTON_ROD.equals(material)) {
      if (face == facing || face == facing.opposite()) names.add("piston_inner");
      else names.add("piston_side");
    } else if ("piston".equals(name) || "sticky_piston".equals(name)) {
      if (face == facing) names.add("sticky_piston".equals(name) ? "piston_top_sticky" : "piston_top");
      else if (face == facing.opposite()) names.add("piston_bottom");
      else names.add("piston_side");
    } else if ("piston_head".equals(name) || "moving_piston".equals(name)) {
      boolean sticky = "sticky".equals(property(block.properties, "type"));
      if (face == facing) names.add(sticky ? "piston_top_sticky" : "piston_top");
      else if (face == facing.opposite()) names.add("piston_inner");
      else names.add("piston_side");
    } else if ("observer".equals(name)) {
      if (face == facing) names.add("observer_front");
      else if (face == facing.opposite()) names.add(truth(block.properties, "powered") ? "observer_back_on" : "observer_back");
      else if (face == BlockFace.UP || face == BlockFace.DOWN) names.add("observer_top");
      else names.add("observer_side");
    } else if ("dispenser".equals(name) || "dropper".equals(name)) {
      if (face == facing) names.add(name + "_front");
      else names.add(name + "_side");
    } else if ("hopper".equals(name)) {
      names.add(face == BlockFace.UP ? "hopper_top" : "hopper_outside");
    } else if ("grass_block".equals(name)) {
      names.add(face == BlockFace.UP ? "grass_block_top" : face == BlockFace.DOWN ? "dirt" : "grass_block_side");
    } else if ("dirt_path".equals(name)) {
      names.add(face == BlockFace.UP ? "dirt_path_top" : "dirt_path_side");
    } else if ("redstone_wire".equals(name)) {
      names.add("redstone_dust_dot");
    } else if ("repeater".equals(name) || "comparator".equals(name)) {
      names.add(name);
      names.add("smooth_stone_slab_side");
    } else if (name.endsWith("_rail") || "rail".equals(name)) {
      names.add(name);
      names.add("rail");
    } else if (name.endsWith("_torch") || "torch".equals(name)) {
      names.add(name);
      if (name.endsWith("_wall_torch")) names.add(name.replace("_wall_torch", "_torch"));
      names.add("torch");
    } else if (name.endsWith("_fence")) {
      names.add(name.replace("_fence", "_planks"));
      names.add(name);
    } else if (name.endsWith("_wall")) {
      names.add(name.substring(0, name.length() - 5));
      names.add(name);
    } else if (name.endsWith("_pane")) {
      names.add(name.replace("_pane", ""));
      names.add("glass");
    } else if (name.endsWith("_carpet")) {
      names.add(name.replace("_carpet", "_wool"));
      names.add(name.replace("_carpet", ""));
    } else if (name.endsWith("_wall_banner") || name.endsWith("_banner")) {
      names.add(name.replace("_wall_banner", "_wool").replace("_banner", "_wool"));
      names.add("white_wool");
    } else if (name.endsWith("_slab")) {
      names.add(name.substring(0, name.length() - 5));
      names.add(name);
    } else if (name.endsWith("_stairs")) {
      names.add(name.substring(0, name.length() - 7));
      names.add(name);
    } else if (name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_stem") || name.endsWith("_hyphae")) {
      boolean end = ("y".equals(axis) && (face == BlockFace.UP || face == BlockFace.DOWN))
        || ("x".equals(axis) && (face == BlockFace.EAST || face == BlockFace.WEST))
        || ("z".equals(axis) && (face == BlockFace.NORTH || face == BlockFace.SOUTH));
      names.add(name + (end ? "_top" : ""));
    } else if (name.endsWith("_trapdoor")) {
      names.add(name);
      names.add(name.replace("_trapdoor", "_planks"));
    } else if (name.endsWith("_button")) {
      names.add(name.replace("_button", ""));
      names.add(name);
    } else {
      names.add(name);
    }
    ArrayList<String> result = new ArrayList<String>();
    for (String candidate : names) {
      if (candidate == null || candidate.trim().isEmpty()) continue;
      String resource = candidate.indexOf('/') >= 0
        ? "assets/" + namespace + "/textures/" + candidate + ".png"
        : "assets/" + namespace + "/textures/block/" + candidate + ".png";
      if (!result.contains(resource)) result.add(resource);
    }
    return result;
  }

  private void railTextureCandidates(List<String> names, String name, boolean powered) {
    if ("powered_rail".equals(name)) {
      if (powered) names.add("powered_rail_on");
      names.add("powered_rail");
      if (!powered) names.add("powered_rail_off");
      return;
    }
    if ("detector_rail".equals(name)) {
      if (powered) names.add("detector_rail_on");
      names.add("detector_rail");
      if (!powered) names.add("detector_rail_off");
      return;
    }
    if ("activator_rail".equals(name)) {
      if (powered) names.add("activator_rail_on");
      names.add("activator_rail");
      if (!powered) names.add("activator_rail_off");
      return;
    }
    names.add(name);
    names.add("rail");
  }

  private void addItemTextureCandidates(List<String> names, String itemId) {
    String value = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    int colon = value.indexOf(':');
    String item = colon >= 0 ? value.substring(colon + 1) : value;
    if (item.isEmpty()) return;
    names.add("item/" + item);
    names.add("block/" + item);
    if (item.endsWith("_block")) names.add("block/" + item.substring(0, item.length() - 6));
  }

  private String signWood(String name) {
    String value = name == null ? "oak" : name;
    String[] suffixes = new String[] { "_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_sign" };
    for (String suffix : suffixes) if (value.endsWith(suffix)) {
      value = value.substring(0, value.length() - suffix.length());
      return value.isEmpty() ? "oak" : value;
    }
    return "oak";
  }

  int rotation(LitematicModel.Block block, BlockFace face, String material) {
    BlockFace facing = BlockFace.fromProperty(block.properties.get("facing"), BlockFace.NORTH);
    String name = localName(block.name);
    if (face == BlockFace.UP && (MachineGeometry.REPEATER_BASE.equals(material) || MachineGeometry.COMPARATOR_BASE.equals(material))) {
      return horizontalRotation(facing);
    }
    if (face == BlockFace.UP && (MachineGeometry.CHEST_LID.equals(material) || MachineGeometry.CHEST_DOUBLE_LID.equals(material)
      || MachineGeometry.CHEST_BOTTOM.equals(material) || MachineGeometry.CHEST_DOUBLE_BOTTOM.equals(material))) {
      return horizontalRotation(facing);
    }
    if (MachineGeometry.FLUID_WATER_FLOW.equals(material) || MachineGeometry.FLUID_LAVA_FLOW.equals(material)) return 0;
    if (MachineGeometry.RAIL_FLAT.equals(material) || MachineGeometry.RAIL_ASCENDING.equals(material)) {
      return railRotation(property(block.properties, "shape"));
    }
    if (MachineGeometry.RAIL_CURVED.equals(material)) return 0;
    if ("observer".equals(name)) {
      return directionalModelRotation(face, facing);
    }
    if (face == BlockFace.UP && (name.endsWith("_rail") || "rail".equals(name))) {
      return railRotation(property(block.properties, "shape"));
    }
    return 0;
  }

  double textureAngle(LitematicModel.Block block, String material) {
    if (MachineGeometry.FRAME_ITEM.equals(material)) return Math.floorMod(number(block.properties.get("lrs_item_rotation")), 8) * 45d;
    return 0d;
  }

  private int horizontalRotation(BlockFace facing) {
    if (facing == BlockFace.EAST) return 1;
    if (facing == BlockFace.SOUTH) return 2;
    if (facing == BlockFace.WEST) return 3;
    return 0;
  }

  private int railRotation(String shape) {
    if (shape.contains("east_west") || shape.contains("ascending_east") || shape.contains("ascending_west") || shape.contains("south_east")) return 1;
    if (shape.contains("south_west")) return 2;
    if (shape.contains("north_west")) return 3;
    return 0;
  }

  private int directionalModelRotation(BlockFace worldFace, BlockFace facing) {
    BlockFace localFace = null;
    for (BlockFace candidate : BlockFace.values()) if (transformFace(candidate, facing) == worldFace) localFace = candidate;
    if (localFace == null) return 0;
    int[] sourceU = transform(uvU(localFace), facing);
    int[] sourceV = transform(uvV(localFace), facing);
    int[] targetU = uvU(worldFace);
    int[] targetV = uvV(worldFace);
    if (same(sourceU, targetU) && same(sourceV, targetV)) return 0;
    if (same(sourceU, targetV) && same(sourceV, negate(targetU))) return 1;
    if (same(sourceU, negate(targetU)) && same(sourceV, negate(targetV))) return 2;
    if (same(sourceU, negate(targetV)) && same(sourceV, targetU)) return 3;
    return 0;
  }

  private BlockFace transformFace(BlockFace face, BlockFace facing) {
    int[] vector = transform(new int[] { face.dx, face.dy, face.dz }, facing);
    for (BlockFace candidate : BlockFace.values()) if (candidate.dx == vector[0] && candidate.dy == vector[1] && candidate.dz == vector[2]) return candidate;
    return face;
  }

  private int[] transform(int[] vector, BlockFace facing) {
    int x = vector[0];
    int y = vector[1];
    int z = vector[2];
    if (facing == BlockFace.NORTH) return new int[] { x, z, -y };
    if (facing == BlockFace.SOUTH) return new int[] { x, -z, y };
    if (facing == BlockFace.WEST) return new int[] { -y, z, -x };
    if (facing == BlockFace.EAST) return new int[] { y, z, x };
    if (facing == BlockFace.DOWN) return new int[] { x, -y, -z };
    return new int[] { x, y, z };
  }

  private int[] uvU(BlockFace face) {
    if (face == BlockFace.UP || face == BlockFace.DOWN || face == BlockFace.NORTH) return new int[] { 1, 0, 0 };
    if (face == BlockFace.SOUTH) return new int[] { -1, 0, 0 };
    if (face == BlockFace.EAST) return new int[] { 0, 0, 1 };
    return new int[] { 0, 0, -1 };
  }

  private int[] uvV(BlockFace face) {
    if (face == BlockFace.UP) return new int[] { 0, 0, 1 };
    if (face == BlockFace.DOWN) return new int[] { 0, 0, -1 };
    return new int[] { 0, -1, 0 };
  }

  private int[] negate(int[] vector) {
    return new int[] { -vector[0], -vector[1], -vector[2] };
  }

  private boolean same(int[] left, int[] right) {
    return left[0] == right[0] && left[1] == right[1] && left[2] == right[2];
  }

  private Texture redstoneTexture(LitematicModel.Block block) {
    String dotResource = "assets/minecraft/textures/block/redstone_dust_dot.png";
    String lineResource = "assets/minecraft/textures/block/redstone_dust_line0.png";
    BufferedImage dot = image(dotResource);
    BufferedImage line = image(lineResource);
    boolean found = dot != null || line != null;
    BufferedImage composed = composeRedstone(block.properties, dot, line);
    if (composed == null) return new Texture(null, fallback(block.name), dotResource, false);
    return new Texture(composed, average(composed), dotResource + "+state", found);
  }

  private Texture verticalRedstoneTexture(LitematicModel.Block block) {
    String resource = "assets/minecraft/textures/block/redstone_dust_line0.png";
    BufferedImage line = image(resource);
    Color base = line == null ? new Color(166, 38, 31) : average(line);
    BufferedImage output = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        if (x < 4 || x > 11) continue;
        int source = sample(line, 8, 15 - y);
        int alpha = (source >>> 24) & 255;
        Color pixel = alpha == 0 ? base : new Color(source, true);
        int change = ((x + y) & 1) == 0 ? 12 : -7;
        int red = Math.max(0, Math.min(255, pixel.getRed() + change));
        int green = Math.max(0, Math.min(255, pixel.getGreen() + change / 3));
        int blue = Math.max(0, Math.min(255, pixel.getBlue() + change / 4));
        output.setRGB(x, y, new Color(red, green, blue, 255).getRGB());
      }
    }
    return new Texture(output, average(output), resource + "+vertical", line != null);
  }

  private BufferedImage signTextTexture(LitematicModel.Block block) {
    String raw = String.valueOf(block.properties.get("lrs_sign_text") == null ? "" : block.properties.get("lrs_sign_text")).trim();
    BufferedImage output = new BufferedImage(128, 80, BufferedImage.TYPE_INT_ARGB);
    if (raw.isEmpty()) return output;
    Graphics2D graphics = output.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(signTextColor(block));
      String[] lines = raw.replace("\\r", "").split("\\n", -1);
      int count = Math.min(4, lines.length);
      int fontSize = count >= 4 ? 16 : count == 3 ? 19 : 23;
      graphics.setFont(new Font("SansSerif", Font.BOLD, fontSize));
      java.awt.FontMetrics metrics = graphics.getFontMetrics();
      int step = Math.max(15, 64 / Math.max(1, count));
      for (int index = 0; index < count; index++) {
        String line = lines[index];
        if (line.length() > 14) line = line.substring(0, 14);
        int width = metrics.stringWidth(line);
        graphics.drawString(line, Math.max(2, (128 - width) / 2), 9 + step * (index + 1));
      }
    } finally {
      graphics.dispose();
    }
    return output;
  }

  private Color signTextColor(LitematicModel.Block block) {
    String color = property(block.properties, "lrs_sign_color");
    if ("white".equals(color)) return new Color(244, 244, 244, 255);
    if ("red".equals(color)) return new Color(178, 40, 36, 255);
    if ("blue".equals(color)) return new Color(60, 92, 178, 255);
    if ("green".equals(color)) return new Color(61, 123, 70, 255);
    if (truth(block.properties, "lrs_sign_glowing")) return new Color(255, 224, 92, 255);
    return new Color(43, 32, 20, 235);
  }

  private Texture chestTexture(LitematicModel.Block block, BlockFace face, String material) {
    String name = localName(block.name);
    boolean doubleChest = MachineGeometry.CHEST_DOUBLE_BOTTOM.equals(material) || MachineGeometry.CHEST_DOUBLE_LID.equals(material);
    String variant = name.contains("ender") ? "ender" : name.contains("trapped") ? "trapped" : "normal";
    String resource = "assets/minecraft/textures/entity/chest/" + variant + (doubleChest ? "_double" : "") + ".png";
    BufferedImage atlas = image(resource);
    if (atlas == null && doubleChest) {
      doubleChest = false;
      resource = "assets/minecraft/textures/entity/chest/" + variant + ".png";
      atlas = image(resource);
    }
    if (atlas == null) return new Texture(null, fallback(block.name), resource, false);
    BlockFace local = localChestFace(face, BlockFace.fromProperty(block.properties.get("facing"), BlockFace.NORTH));
    boolean lid = MachineGeometry.CHEST_LID.equals(material) || MachineGeometry.CHEST_DOUBLE_LID.equals(material);
    int[] area = doubleChest ? doubleChestArea(block, local, lid) : singleChestArea(local, lid);
    BufferedImage crop = cropAtlas(atlas, area);
    return new Texture(crop, average(crop), resource + "#" + area[0] + "," + area[1] + "," + area[2] + "," + area[3], true);
  }

  private boolean isChestMaterial(String material) {
    return MachineGeometry.CHEST_BOTTOM.equals(material) || MachineGeometry.CHEST_LID.equals(material)
      || MachineGeometry.CHEST_DOUBLE_BOTTOM.equals(material) || MachineGeometry.CHEST_DOUBLE_LID.equals(material);
  }

  private int[] singleChestArea(BlockFace local, boolean lid) {
    if (lid) {
      if (local == BlockFace.UP) return new int[] { 14, 0, 28, 14 };
      if (local == BlockFace.DOWN) return new int[] { 28, 0, 42, 14 };
      if (local == BlockFace.WEST) return new int[] { 0, 14, 14, 19 };
      if (local == BlockFace.NORTH) return new int[] { 14, 14, 28, 19 };
      if (local == BlockFace.EAST) return new int[] { 28, 14, 42, 19 };
      return new int[] { 42, 14, 56, 19 };
    }
    if (local == BlockFace.UP) return new int[] { 14, 19, 28, 33 };
    if (local == BlockFace.DOWN) return new int[] { 28, 19, 42, 33 };
    if (local == BlockFace.WEST) return new int[] { 0, 33, 14, 43 };
    if (local == BlockFace.NORTH) return new int[] { 14, 33, 28, 43 };
    if (local == BlockFace.EAST) return new int[] { 28, 33, 42, 43 };
    return new int[] { 42, 33, 56, 43 };
  }

  private int[] doubleChestArea(LitematicModel.Block block, BlockFace local, boolean lid) {
    int half = "left".equals(property(block.properties, "type")) ? 0 : 15;
    if (lid) {
      if (local == BlockFace.UP || local == BlockFace.DOWN) return new int[] { 14 + half, local == BlockFace.UP ? 0 : 19, 29 + half, local == BlockFace.UP ? 14 : 33 };
      if (local == BlockFace.NORTH || local == BlockFace.SOUTH) return new int[] { 14 + half, local == BlockFace.NORTH ? 14 : 33, 29 + half, local == BlockFace.NORTH ? 19 : 43 };
      return local == BlockFace.WEST ? new int[] { 0, 14, 14, 19 } : new int[] { 44, 14, 58, 19 };
    }
    if (local == BlockFace.UP || local == BlockFace.DOWN) return new int[] { 14 + half, local == BlockFace.UP ? 19 : 0, 29 + half, local == BlockFace.UP ? 33 : 14 };
    if (local == BlockFace.NORTH || local == BlockFace.SOUTH) return new int[] { 14 + half, local == BlockFace.NORTH ? 33 : 14, 29 + half, local == BlockFace.NORTH ? 43 : 19 };
    return local == BlockFace.WEST ? new int[] { 0, 33, 14, 43 } : new int[] { 44, 33, 58, 43 };
  }

  private BufferedImage cropAtlas(BufferedImage atlas, int[] area) {
    double scaleX = atlas.getWidth() / 64d;
    double scaleY = atlas.getHeight() / 64d;
    int x = Math.max(0, Math.min(atlas.getWidth() - 1, (int) Math.round(area[0] * scaleX)));
    int y = Math.max(0, Math.min(atlas.getHeight() - 1, (int) Math.round(area[1] * scaleY)));
    int maxX = Math.max(x + 1, Math.min(atlas.getWidth(), (int) Math.round(area[2] * scaleX)));
    int maxY = Math.max(y + 1, Math.min(atlas.getHeight(), (int) Math.round(area[3] * scaleY)));
    BufferedImage result = new BufferedImage(maxX - x, maxY - y, BufferedImage.TYPE_INT_ARGB);
    for (int py = 0; py < result.getHeight(); py++) for (int px = 0; px < result.getWidth(); px++) result.setRGB(px, py, atlas.getRGB(x + px, y + py));
    return result;
  }

  private BlockFace localChestFace(BlockFace world, BlockFace facing) {
    if (world == BlockFace.UP || world == BlockFace.DOWN) return world;
    for (BlockFace candidate : new BlockFace[] { BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST }) {
      if (rotateHorizontalFace(candidate, facing) == world) return candidate;
    }
    return world;
  }

  private BlockFace rotateHorizontalFace(BlockFace face, BlockFace facing) {
    if (facing == BlockFace.EAST) return face == BlockFace.NORTH ? BlockFace.EAST : face == BlockFace.EAST ? BlockFace.SOUTH : face == BlockFace.SOUTH ? BlockFace.WEST : BlockFace.NORTH;
    if (facing == BlockFace.SOUTH) return face.opposite();
    if (facing == BlockFace.WEST) return face == BlockFace.NORTH ? BlockFace.WEST : face == BlockFace.WEST ? BlockFace.SOUTH : face == BlockFace.SOUTH ? BlockFace.EAST : BlockFace.NORTH;
    return face;
  }

  private Texture torchTexture(LitematicModel.Block block, String material) {
    boolean off = MachineGeometry.TORCH_HEAD_OFF.equals(material);
    String resource = "assets/minecraft/textures/block/" + (off ? "redstone_torch_off" : "redstone_torch") + ".png";
    BufferedImage source = image(resource);
    if (source == null && off) {
      resource = "assets/minecraft/textures/block/redstone_torch.png";
      source = image(resource);
    }
    Color base = source == null ? (off ? new Color(78, 48, 43) : new Color(224, 61, 42)) : average(source);
    if (MachineGeometry.TORCH_STEM.equals(material)) base = mix(base, new Color(105, 65, 37, 255), 0.58f);
    BufferedImage output = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        int texture = source == null ? 0 : sample(source, x, y);
        int alpha = (texture >>> 24) & 255;
        Color pixel = alpha == 0 ? base : new Color(texture, true);
        if (MachineGeometry.TORCH_STEM.equals(material)) pixel = mix(pixel, base, 0.68f);
        int change = ((x + y) & 1) == 0 ? 8 : -7;
        output.setRGB(x, y, new Color(
          Math.max(0, Math.min(255, pixel.getRed() + change)),
          Math.max(0, Math.min(255, pixel.getGreen() + change)),
          Math.max(0, Math.min(255, pixel.getBlue() + change)),
          255
        ).getRGB());
      }
    }
    return new Texture(output, average(output), resource + "+torch", source != null);
  }

  private BufferedImage prepare(BufferedImage image, String material) {
    boolean fluid = MachineGeometry.FLUID_WATER.equals(material) || MachineGeometry.FLUID_WATER_FLOW.equals(material)
      || MachineGeometry.FLUID_LAVA.equals(material) || MachineGeometry.FLUID_LAVA_FLOW.equals(material);
    if (!fluid) return image;
    int sourceWidth = image.getWidth();
    int sourceHeight = image.getHeight();
    if (sourceHeight > sourceWidth) sourceHeight = sourceWidth;
    int width = Math.max(1, sourceWidth);
    int height = Math.max(1, sourceHeight);
    BufferedImage output = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        int sourceX = Math.min(image.getWidth() - 1, (int) ((x + 0.5d) * width / 16d));
        int sourceY = Math.min(image.getHeight() - 1, (int) ((y + 0.5d) * height / 16d));
        output.setRGB(x, y, image.getRGB(sourceX, sourceY));
      }
    }
    return output;
  }

  private BufferedImage composeRedstone(Map<String, Object> properties, BufferedImage dot, BufferedImage line) {
    boolean north = connected(properties, "north");
    boolean south = connected(properties, "south");
    boolean east = connected(properties, "east");
    boolean west = connected(properties, "west");
    BufferedImage output = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    boolean any = north || south || east || west;
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        int pixel = 0;
        if (!any) {
          pixel = sample(dot, x, y);
          if (((pixel >>> 24) & 255) == 0 && dot == null && x >= 5 && x <= 10 && y >= 5 && y <= 10) pixel = 0xffffffff;
        } else {
          boolean center = x >= 5 && x <= 10 && y >= 5 && y <= 10;
          if (north && x >= 5 && x <= 10 && y <= 8) pixel = over(pixel, sample(line, x, y));
          if (south && x >= 5 && x <= 10 && y >= 7) pixel = over(pixel, sample(line, x, y));
          if (west && y >= 5 && y <= 10 && x <= 8) pixel = over(pixel, sampleRotatedClockwise(line, x, y));
          if (east && y >= 5 && y <= 10 && x >= 7) pixel = over(pixel, sampleRotatedClockwise(line, x, y));
          if (center) pixel = over(pixel, sample(dot, x, y));
          if (((pixel >>> 24) & 255) == 0 && ((center) || (north && x >= 5 && x <= 10 && y <= 8) || (south && x >= 5 && x <= 10 && y >= 7)
            || (west && y >= 5 && y <= 10 && x <= 8) || (east && y >= 5 && y <= 10 && x >= 7))) pixel = 0xffffffff;
        }
        output.setRGB(x, y, pixel);
      }
    }
    return output;
  }

  private int sample(BufferedImage image, int x, int y) {
    if (image == null) return 0;
    int sourceX = Math.min(image.getWidth() - 1, Math.max(0, (int) ((x + 0.5d) * image.getWidth() / 16d)));
    int sourceY = Math.min(image.getHeight() - 1, Math.max(0, (int) ((y + 0.5d) * image.getHeight() / 16d)));
    return image.getRGB(sourceX, sourceY);
  }

  private int sampleRotatedClockwise(BufferedImage image, int x, int y) {
    return sample(image, y, 15 - x);
  }

  private int over(int bottom, int top) {
    int topAlpha = (top >>> 24) & 255;
    return topAlpha == 0 ? bottom : top;
  }

  private boolean connected(Map<String, Object> properties, String direction) {
    String value = property(properties, direction);
    return "side".equals(value) || "up".equals(value) || "true".equals(value);
  }

  private boolean truth(Map<String, Object> properties, String key) {
    String value = property(properties, key);
    return "true".equals(value) || "1".equals(value);
  }

  private String property(Map<String, Object> properties, String key) {
    Object value = properties.get(key);
    return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
  }

  private String localName(String raw) {
    String value = raw == null ? "" : raw;
    int colon = value.indexOf(':');
    return (colon >= 0 ? value.substring(colon + 1) : value).toLowerCase(Locale.ROOT);
  }

  private int number(Object value) {
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return 0;
    }
  }

  private BufferedImage image(String resource) {
    for (int index = packs.size() - 1; index >= 0; index--) {
      BufferedImage image = imageFrom(packs.get(index), resource);
      if (image != null) return image;
    }
    return clientJar == null ? null : imageFrom(clientJar, resource);
  }

  String text(String resource) {
    for (int index = packs.size() - 1; index >= 0; index--) {
      String value = textFrom(packs.get(index), resource);
      if (value != null) return value;
    }
    return clientJar == null ? null : textFrom(clientJar, resource);
  }

  private String textFrom(Path pack, String resource) {
    try {
      if (Files.isDirectory(pack)) {
        Path candidate = pack.resolve(resource.replace('/', java.io.File.separatorChar));
        return Files.isRegularFile(candidate) ? new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8) : null;
      }
      ZipFile archive = archives.get(pack);
      if (archive == null) {
        archive = new ZipFile(pack.toFile());
        archives.put(pack, archive);
      }
      ZipEntry entry = archive.getEntry(resource);
      if (entry == null) return null;
      InputStream stream = archive.getInputStream(entry);
      try {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(256, (int) Math.min(1_000_000L, entry.getSize())));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
      } finally {
        stream.close();
      }
    } catch (Exception ignored) {
      return null;
    }
  }

  private BufferedImage imageFrom(Path pack, String resource) {
    try {
      if (Files.isDirectory(pack)) {
        Path candidate = pack.resolve(resource.replace('/', java.io.File.separatorChar));
        return Files.isRegularFile(candidate) ? ImageIO.read(candidate.toFile()) : null;
      }
      ZipFile archive = archives.get(pack);
      if (archive == null) {
        archive = new ZipFile(pack.toFile());
        archives.put(pack, archive);
      }
      ZipEntry entry = archive.getEntry(resource);
      if (entry == null) return null;
      InputStream stream = archive.getInputStream(entry);
      try {
        return ImageIO.read(stream);
      } finally {
        stream.close();
      }
    } catch (Exception ignored) {
      return null;
    }
  }

  private Color average(BufferedImage image) {
    long red = 0;
    long green = 0;
    long blue = 0;
    long alpha = 0;
    long weight = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int argb = image.getRGB(x, y);
        int a = (argb >>> 24) & 255;
        if (a == 0) continue;
        red += ((argb >>> 16) & 255) * (long) a;
        green += ((argb >>> 8) & 255) * (long) a;
        blue += (argb & 255) * (long) a;
        alpha += a;
        weight += a;
      }
    }
    if (weight == 0) return new Color(180, 180, 180, 0);
    return new Color((int) (red / weight), (int) (green / weight), (int) (blue / weight), (int) Math.min(255L, alpha / Math.max(1L, image.getWidth() * (long) image.getHeight())));
  }

  private Color fallback(String rawName) {
    String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
    if (name.contains("redstone")) return new Color(167, 41, 38);
    if (name.contains("piston")) return new Color(143, 118, 83);
    if (name.contains("observer")) return new Color(92, 92, 91);
    if (name.contains("repeater") || name.contains("comparator")) return new Color(206, 203, 190);
    if (name.contains("hopper")) return new Color(78, 82, 86);
    if (name.contains("minecart")) return new Color(126, 132, 136);
    if (name.contains("torch")) return new Color(226, 158, 63);
    if (name.contains("slime")) return new Color(109, 181, 76);
    if (name.contains("honey")) return new Color(229, 159, 33);
    if (name.contains("glass")) return new Color(174, 213, 227, 140);
    if (name.contains("water")) return new Color(64, 110, 220, 145);
    if (name.contains("lava")) return new Color(238, 93, 25);
    if (name.equals("vine") || name.contains("cave_vines")) return new Color(84, 158, 61);
    if (name.contains("obsidian")) return new Color(51, 41, 73);
    if (name.contains("quartz")) return new Color(227, 221, 210);
    if (name.contains("iron")) return new Color(209, 210, 206);
    if (name.contains("gold")) return new Color(237, 184, 54);
    if (name.contains("copper")) return new Color(184, 110, 72);
    if (name.contains("wood") || name.contains("log") || name.contains("planks") || name.contains("chest")) return new Color(151, 111, 67);
    if (name.contains("dirt") || name.contains("mud")) return new Color(128, 93, 64);
    if (name.contains("grass") || name.contains("leaves")) return new Color(100, 148, 67);
    if (name.contains("white_")) return new Color(224, 226, 226);
    if (name.contains("orange_")) return new Color(241, 118, 20);
    if (name.contains("magenta_")) return new Color(190, 68, 201);
    if (name.contains("light_blue_")) return new Color(58, 175, 217);
    if (name.contains("yellow_")) return new Color(249, 198, 39);
    if (name.contains("lime_")) return new Color(112, 185, 25);
    if (name.contains("pink_")) return new Color(238, 141, 172);
    if (name.contains("light_gray_")) return new Color(142, 142, 134);
    if (name.contains("gray_")) return new Color(62, 68, 71);
    if (name.contains("cyan_")) return new Color(21, 137, 145);
    if (name.contains("purple_")) return new Color(121, 42, 172);
    if (name.contains("blue_")) return new Color(53, 57, 157);
    if (name.contains("brown_")) return new Color(114, 71, 40);
    if (name.contains("green_")) return new Color(84, 109, 27);
    if (name.contains("red_")) return new Color(161, 39, 34);
    if (name.contains("black_")) return new Color(22, 22, 26);
    if (name.contains("deepslate")) return new Color(69, 69, 73);
    return new Color(133, 133, 133);
  }

  private Color mix(Color left, Color right, float amount) {
    float inverse = 1f - amount;
    return new Color(
      Math.min(255, Math.round(left.getRed() * inverse + right.getRed() * amount)),
      Math.min(255, Math.round(left.getGreen() * inverse + right.getGreen() * amount)),
      Math.min(255, Math.round(left.getBlue() * inverse + right.getBlue() * amount)),
      Math.min(255, Math.round(left.getAlpha() * inverse + right.getAlpha() * amount))
    );
  }

  private Color multiply(Color source, Color tint) {
    return new Color(source.getRed() * tint.getRed() / 255, source.getGreen() * tint.getGreen() / 255,
      source.getBlue() * tint.getBlue() / 255, Math.min(source.getAlpha(), tint.getAlpha()));
  }

  private int luminance(Color color) {
    return (color.getRed() * 54 + color.getGreen() * 183 + color.getBlue() * 19) / 256;
  }

  private int saturation(Color color) {
    int high = Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue()));
    int low = Math.min(color.getRed(), Math.min(color.getGreen(), color.getBlue()));
    return high - low;
  }

  private int clampColor(double value) {
    return Math.max(0, Math.min(255, (int) Math.round(value)));
  }

  public void close() {
    for (ZipFile archive : archives.values()) {
      try {
        archive.close();
      } catch (Exception ignored) {
      }
    }
    archives.clear();
  }
}

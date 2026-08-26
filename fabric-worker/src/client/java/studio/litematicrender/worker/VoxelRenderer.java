package studio.litematicrender.worker;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;

final class VoxelRenderer {
  interface Progress {
    void report(String stage, String message, double fraction);
  }

  private VoxelRenderer() {
  }

  static List<Path> render(Map<String, Object> job, Path clientJar, Progress progress) throws Exception {
    RenderJob parsed = RenderJob.parse(job);
    progress.report("read_litematic", "正在读取 .litematic 投影文件…", 0.03d);
    LitematicModel model = LitematicModel.read(parsed.litematic);
    String framing = model.omittedFramingBlocks() > 0
      ? "；自动取景已排除 " + model.omittedFramingBlocks() + " 个离散方块"
      : "";
    progress.report("prepare_scene", "已展开 " + model.blocks.size() + " 个非空气方块、" + model.entities.size() + " 个实体；正在准备视角与资源包颜色…" + framing, 0.12d);
    ArrayList<Path> outputs = new ArrayList<Path>();
    if (parsed.parallelDirections && parsed.views.size() > 1) {
      renderParallel(model, parsed, clientJar, progress, outputs);
    } else {
      renderSequential(model, parsed, clientJar, progress, outputs);
    }
    progress.report("write_png", "PNG 已写入 " + outputs.size() + " 个文件。", 0.97d);
    return outputs;
  }

  private static void renderSequential(LitematicModel model, RenderJob parsed, Path clientJar, Progress progress, List<Path> outputs) throws Exception {
    TextureResolver textures = new TextureResolver(parsed.resourcePacks, clientJar);
    try {
      int total = parsed.views.size();
      for (int index = 0; index < total; index++) {
        ViewSpec view = parsed.views.get(index);
        double start = 0.12d + 0.80d * index / Math.max(1, total);
        progress.report("render", "正在渲染 " + view.label + "（" + (index + 1) + "/" + total + "）…", start);
        Path output = parsed.outputDirectory.resolve(fileName(parsed.namingPrefix, parsed.projection, view, parsed.width, parsed.height));
        renderView(model, textures, parsed, view, output, new ViewProgress(progress, start, 0.80d / Math.max(1, total), view.label));
        outputs.add(output);
      }
      progress.report("textures", textures.report(), 0.955d);
    } finally {
      textures.close();
    }
  }

  private static void renderParallel(final LitematicModel model, final RenderJob parsed, final Path clientJar, final Progress progress, List<Path> outputs) throws Exception {
    final int total = parsed.views.size();
    progress.report("render", "正在同时渲染 " + total + " 个方向；此模式会显著增加内存与 CPU 占用…", 0.12d);
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, total));
    ArrayList<Future<Path>> futures = new ArrayList<Future<Path>>();
    try {
      for (int index = 0; index < total; index++) {
        final int position = index;
        final ViewSpec view = parsed.views.get(index);
        futures.add(executor.submit(new Callable<Path>() {
          public Path call() throws Exception {
            TextureResolver textures = new TextureResolver(parsed.resourcePacks, clientJar);
            try {
              double start = 0.12d + 0.80d * position / Math.max(1, total);
              Path output = parsed.outputDirectory.resolve(fileName(parsed.namingPrefix, parsed.projection, view, parsed.width, parsed.height));
              renderView(model, textures, parsed, view, output, new ViewProgress(progress, start, 0.80d / Math.max(1, total), view.label));
              progress.report("textures", view.label + "：" + textures.report(), 0.94d + 0.01d * position);
              return output;
            } finally {
              textures.close();
            }
          }
        }));
      }
      for (Future<Path> future : futures) outputs.add(future.get());
    } finally {
      executor.shutdownNow();
    }
  }

  static void renderView(LitematicModel model, TextureResolver textures, RenderJob job, ViewSpec view, Path output, Progress progress) throws Exception {
    long pixels = (long) job.width * (long) job.height;
    if (pixels > 140_000_000L) throw new IllegalArgumentException("单张 PNG 上限为 1.4 亿像素；请降低分辨率或使用更小的自定义尺寸。");
    BufferedImage image = new BufferedImage(job.width, job.height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      if ("white".equals(job.background)) {
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, job.width, job.height);
        graphics.setComposite(AlphaComposite.SrcOver);
      } else {
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, job.width, job.height);
        graphics.setComposite(AlphaComposite.SrcOver);
      }
      Camera camera = Camera.create(model, job, view.azimuth);
      ArrayList<DepthPart> ordered = new ArrayList<DepthPart>(model.blocks.size() * 3 + model.entities.size() * 12);
      for (LitematicModel.Block block : model.blocks) {
        MachineGeometry.Shape shape = textures.shape(model, block);
        for (MachineGeometry.Part part : shape.parts) {
          MachineGeometry.Box world = translate(block, part.box);
          ordered.add(new DepthPart(block, shape, part, world, camera.depth(world.centerX(), world.centerY(), world.centerZ()), false));
        }
      }
      for (LitematicModel.Entity entity : model.entities) {
        LitematicModel.Block renderBlock = entity.renderBlock();
        MachineGeometry.Shape shape = textures.shape(model, renderBlock);
        for (MachineGeometry.Part part : shape.parts) {
          MachineGeometry.Box world = translate(entity, part.box);
          ordered.add(new DepthPart(renderBlock, shape, part, world, camera.depth(world.centerX(), world.centerY(), world.centerZ()), true));
        }
      }
      Collections.sort(ordered, new Comparator<DepthPart>() {
        public int compare(DepthPart left, DepthPart right) {
          int depth = Double.compare(right.depth, left.depth);
          if (depth != 0) return depth;
          int y = Double.compare(left.world.centerY(), right.world.centerY());
          if (y != 0) return y;
          int x = Double.compare(left.world.centerX(), right.world.centerX());
          if (x != 0) return x;
          int z = Double.compare(left.world.centerZ(), right.world.centerZ());
          if (z != 0) return z;
          return Boolean.compare(left.entity, right.entity);
        }
      });
      progress.report("raster", view.label + "：正在按 Y=上下坐标绘制状态几何与可见方块面…", 0.12d);
      int count = ordered.size();
      for (int index = 0; index < count; index++) {
        drawPart(graphics, model, textures, job, camera, ordered.get(index));
        if (index > 0 && index % 12000 == 0) progress.report("raster", view.label + "：已处理 " + index + " / " + count + " 个几何部件…", 0.12d + 0.78d * index / Math.max(1, count));
      }
    } finally {
      graphics.dispose();
    }
    Files.createDirectories(output.getParent());
    if (!ImageIO.write(image, "png", output.toFile())) throw new IllegalArgumentException("Java ImageIO 无法写入 PNG。 ");
    progress.report("write_png", view.label + "：已输出 " + output.getFileName(), 0.98d);
  }

  private static void drawPart(Graphics2D graphics, LitematicModel model, TextureResolver textures, RenderJob job, Camera camera, DepthPart value) {
    LitematicModel.Block block = value.block;
    MachineGeometry.Shape shape = value.shape;
    MachineGeometry.Part part = value.part;
    if (camera.bottom) {
      if (part.shows(BlockFace.DOWN) && (value.entity || !hiddenByNeighbor(model, textures, block, shape, part.box, BlockFace.DOWN))) {
        drawFace(graphics, textures, block, part, job.lighting, BlockFace.DOWN, camera, corners(value.world, part, BlockFace.DOWN));
      }
      return;
    }
    BlockFace sideX = camera.outX >= 0d ? BlockFace.EAST : BlockFace.WEST;
    BlockFace sideZ = camera.outZ >= 0d ? BlockFace.SOUTH : BlockFace.NORTH;
    if (part.shows(BlockFace.UP) && (value.entity || !hiddenByNeighbor(model, textures, block, shape, part.box, BlockFace.UP))) drawFace(graphics, textures, block, part, job.lighting, BlockFace.UP, camera, corners(value.world, part, BlockFace.UP));
    if (part.shows(sideX) && (value.entity || !hiddenByNeighbor(model, textures, block, shape, part.box, sideX))) drawFace(graphics, textures, block, part, job.lighting, sideX, camera, corners(value.world, part, sideX));
    if (part.shows(sideZ) && (value.entity || !hiddenByNeighbor(model, textures, block, shape, part.box, sideZ))) drawFace(graphics, textures, block, part, job.lighting, sideZ, camera, corners(value.world, part, sideZ));
  }

  private static boolean hiddenByNeighbor(LitematicModel model, TextureResolver textures, LitematicModel.Block block, MachineGeometry.Shape shape, MachineGeometry.Box box, BlockFace face) {
    LitematicModel.Block neighbor = model.blockAt(block.x + face.dx, block.y + face.dy, block.z + face.dz);
    if (shape.fullCube && neighbor != null && block.name.equals(neighbor.name)) return true;
    if (!shape.fullCube || !box.isFullBlock()) return false;
    return textures.occludes(model, neighbor);
  }

  private static MachineGeometry.Box translate(LitematicModel.Block block, MachineGeometry.Box box) {
    return new MachineGeometry.Box(block.x + box.minX, block.y + box.minY, block.z + box.minZ, block.x + box.maxX, block.y + box.maxY, block.z + box.maxZ);
  }

  private static MachineGeometry.Box translate(LitematicModel.Entity entity, MachineGeometry.Box box) {
    double baseY = entity.renderBaseY();
    return new MachineGeometry.Box(entity.x - 0.5d + box.minX, baseY + box.minY, entity.z - 0.5d + box.minZ,
      entity.x - 0.5d + box.maxX, baseY + box.maxY, entity.z - 0.5d + box.maxZ);
  }

  private static Point3[] corners(MachineGeometry.Box box, MachineGeometry.Part part, BlockFace face) {
    if (part.topHeights != null) return fluidCorners(box, part.topHeights, face);
    if (face == BlockFace.UP && part.slopeUp != null) return slopeCorners(box, part.slopeUp);
    switch (face) {
      case EAST:
        return new Point3[] {
          new Point3(box.maxX, box.maxY, box.minZ), new Point3(box.maxX, box.maxY, box.maxZ),
          new Point3(box.maxX, box.minY, box.maxZ), new Point3(box.maxX, box.minY, box.minZ)
        };
      case WEST:
        return new Point3[] {
          new Point3(box.minX, box.maxY, box.maxZ), new Point3(box.minX, box.maxY, box.minZ),
          new Point3(box.minX, box.minY, box.minZ), new Point3(box.minX, box.minY, box.maxZ)
        };
      case SOUTH:
        return new Point3[] {
          new Point3(box.maxX, box.maxY, box.maxZ), new Point3(box.minX, box.maxY, box.maxZ),
          new Point3(box.minX, box.minY, box.maxZ), new Point3(box.maxX, box.minY, box.maxZ)
        };
      case NORTH:
        return new Point3[] {
          new Point3(box.minX, box.maxY, box.minZ), new Point3(box.maxX, box.maxY, box.minZ),
          new Point3(box.maxX, box.minY, box.minZ), new Point3(box.minX, box.minY, box.minZ)
        };
      case DOWN:
        return new Point3[] {
          new Point3(box.minX, box.minY, box.maxZ), new Point3(box.maxX, box.minY, box.maxZ),
          new Point3(box.maxX, box.minY, box.minZ), new Point3(box.minX, box.minY, box.minZ)
        };
      default:
        return new Point3[] {
          new Point3(box.minX, box.maxY, box.minZ), new Point3(box.maxX, box.maxY, box.minZ),
          new Point3(box.maxX, box.maxY, box.maxZ), new Point3(box.minX, box.maxY, box.maxZ)
        };
    }
  }

  private static Point3[] fluidCorners(MachineGeometry.Box box, double[] heights, BlockFace face) {
    double nw = box.minY + heights[0];
    double ne = box.minY + heights[1];
    double se = box.minY + heights[2];
    double sw = box.minY + heights[3];
    if (face == BlockFace.UP) return new Point3[] {
      new Point3(box.minX, nw, box.minZ), new Point3(box.maxX, ne, box.minZ),
      new Point3(box.maxX, se, box.maxZ), new Point3(box.minX, sw, box.maxZ)
    };
    if (face == BlockFace.EAST) return new Point3[] {
      new Point3(box.maxX, ne, box.minZ), new Point3(box.maxX, se, box.maxZ),
      new Point3(box.maxX, box.minY, box.maxZ), new Point3(box.maxX, box.minY, box.minZ)
    };
    if (face == BlockFace.WEST) return new Point3[] {
      new Point3(box.minX, sw, box.maxZ), new Point3(box.minX, nw, box.minZ),
      new Point3(box.minX, box.minY, box.minZ), new Point3(box.minX, box.minY, box.maxZ)
    };
    if (face == BlockFace.SOUTH) return new Point3[] {
      new Point3(box.maxX, se, box.maxZ), new Point3(box.minX, sw, box.maxZ),
      new Point3(box.minX, box.minY, box.maxZ), new Point3(box.maxX, box.minY, box.maxZ)
    };
    if (face == BlockFace.NORTH) return new Point3[] {
      new Point3(box.minX, nw, box.minZ), new Point3(box.maxX, ne, box.minZ),
      new Point3(box.maxX, box.minY, box.minZ), new Point3(box.minX, box.minY, box.minZ)
    };
    return corners(box, new MachineGeometry.Part(box, MachineGeometry.BLOCK, MachineGeometry.ALL_FACES, false, false), face);
  }

  private static Point3[] slopeCorners(MachineGeometry.Box box, BlockFace high) {
    if (high == BlockFace.NORTH) return new Point3[] {
      new Point3(box.minX, box.maxY, box.minZ), new Point3(box.maxX, box.maxY, box.minZ),
      new Point3(box.maxX, box.minY, box.maxZ), new Point3(box.minX, box.minY, box.maxZ)
    };
    if (high == BlockFace.SOUTH) return new Point3[] {
      new Point3(box.minX, box.minY, box.minZ), new Point3(box.maxX, box.minY, box.minZ),
      new Point3(box.maxX, box.maxY, box.maxZ), new Point3(box.minX, box.maxY, box.maxZ)
    };
    if (high == BlockFace.EAST) return new Point3[] {
      new Point3(box.minX, box.minY, box.minZ), new Point3(box.maxX, box.maxY, box.minZ),
      new Point3(box.maxX, box.maxY, box.maxZ), new Point3(box.minX, box.minY, box.maxZ)
    };
    return new Point3[] {
      new Point3(box.minX, box.maxY, box.minZ), new Point3(box.maxX, box.minY, box.minZ),
      new Point3(box.maxX, box.minY, box.maxZ), new Point3(box.minX, box.maxY, box.maxZ)
    };
  }

  private static void drawFace(Graphics2D graphics, TextureResolver textures, LitematicModel.Block block, MachineGeometry.Part part, String lighting, BlockFace face, Camera camera, Point3[] points) {
    Point2[] projected = new Point2[4];
    for (int index = 0; index < projected.length; index++) projected[index] = camera.project(points[index]);
    MachineGeometry.FaceSpec faceSpec = part.faceSpec(face);
    TextureResolver.Texture texture = faceSpec == null ? textures.texture(block, face, part.material) : textures.textureResource(block, faceSpec);
    int tintIndex = faceSpec == null ? -1 : faceSpec.tintIndex;
    if (texture.image == null) {
      fill(graphics, polygon(projected), shade(textures.tint(block, part.material, texture.fallback, tintIndex), lighting, face));
    } else {
      if (MachineGeometry.HOPPER_INSIDE.equals(part.material) || MachineGeometry.HOPPER_OUTSIDE.equals(part.material)) {
        Color base = texture.fallback;
        fill(graphics, polygon(projected), shade(new Color(base.getRed(), base.getGreen(), base.getBlue(), 255), lighting, face));
      }
      double rotation = faceSpec == null
        ? textures.rotation(block, face, part.material) * 90d + (face == BlockFace.UP ? part.textureAngleDeg : 0d) + textures.textureAngle(block, part.material)
        : 0d;
      drawTexture(graphics, textures, block, part, lighting, face, projected, texture.image, rotation, faceSpec);
    }
    if (part.outline) {
      Color border = shade(textures.tint(block, part.material, texture.fallback, tintIndex), lighting, face);
      int edgeAlpha = Math.min(84, Math.max(14, border.getAlpha() / 3));
      graphics.setColor(new Color(12, 17, 27, edgeAlpha));
      graphics.draw(polygon(projected));
    }
  }

  private static void drawTexture(Graphics2D graphics, TextureResolver textures, LitematicModel.Block block, MachineGeometry.Part part, String lighting, BlockFace face, Point2[] facePoints, BufferedImage image, double rotation, MachineGeometry.FaceSpec faceSpec) {
    Object oldHint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    int cellsX = faceSpec == null ? (part.cropTexture ? textureCells(part.box, face, true) : Math.max(1, Math.min(16, image.getWidth()))) : uvCells(faceSpec.uv, image, true);
    int cellsY = faceSpec == null ? (part.cropTexture ? textureCells(part.box, face, false) : Math.max(1, Math.min(16, image.getHeight()))) : uvCells(faceSpec.uv, image, false);
    for (int y = 0; y < cellsY; y++) {
      for (int x = 0; x < cellsX; x++) {
        double localU = (x + 0.5d) / cellsX;
        double localV = (y + 0.5d) / cellsY;
        double[] baseUv = faceSpec != null ? interpolateUv(faceSpec.uv, localU, localV)
          : part.cropTexture ? textureUv(part.box, face, localU, localV) : new double[] { localU, localV };
        double[] sourceUv = faceSpec == null ? rotateUv(baseUv[0], baseUv[1], rotation) : baseUv;
        int sourceX = Math.min(image.getWidth() - 1, Math.max(0, (int) (sourceUv[0] * image.getWidth())));
        int sourceY = Math.min(image.getHeight() - 1, Math.max(0, (int) (sourceUv[1] * image.getHeight())));
        Color source = new Color(image.getRGB(sourceX, sourceY), true);
        if (source.getAlpha() == 0) continue;
        Point2[] tile = new Point2[] {
          interpolate(facePoints, x / (double) cellsX, y / (double) cellsY),
          interpolate(facePoints, (x + 1d) / cellsX, y / (double) cellsY),
          interpolate(facePoints, (x + 1d) / cellsX, (y + 1d) / cellsY),
          interpolate(facePoints, x / (double) cellsX, (y + 1d) / cellsY)
        };
        fill(graphics, polygon(tile), shade(textures.tint(block, part.material, source, faceSpec == null ? -1 : faceSpec.tintIndex), lighting, face));
      }
    }
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint == null ? RenderingHints.VALUE_ANTIALIAS_ON : oldHint);
  }

  private static int uvCells(double[] uv, BufferedImage image, boolean horizontal) {
    if (uv == null || uv.length < 8) return Math.max(1, Math.min(16, horizontal ? image.getWidth() : image.getHeight()));
    double du = horizontal ? uv[2] - uv[0] : uv[6] - uv[0];
    double dv = horizontal ? uv[3] - uv[1] : uv[7] - uv[1];
    double pixels = Math.sqrt(du * du * image.getWidth() * image.getWidth() + dv * dv * image.getHeight() * image.getHeight());
    return Math.max(1, Math.min(32, (int) Math.round(pixels)));
  }

  private static double[] interpolateUv(double[] uv, double u, double v) {
    double topU = uv[0] * (1d - u) + uv[2] * u;
    double topV = uv[1] * (1d - u) + uv[3] * u;
    double bottomU = uv[6] * (1d - u) + uv[4] * u;
    double bottomV = uv[7] * (1d - u) + uv[5] * u;
    return new double[] { topU * (1d - v) + bottomU * v, topV * (1d - v) + bottomV * v };
  }

  private static int textureCells(MachineGeometry.Box box, BlockFace face, boolean horizontal) {
    double size;
    if (face == BlockFace.UP || face == BlockFace.DOWN) size = horizontal ? box.maxX - box.minX : box.maxZ - box.minZ;
    else if (face == BlockFace.EAST || face == BlockFace.WEST) size = horizontal ? box.maxZ - box.minZ : box.maxY - box.minY;
    else size = horizontal ? box.maxX - box.minX : box.maxY - box.minY;
    return Math.max(1, Math.min(16, (int) Math.round(size * 16d)));
  }

  private static double[] textureUv(MachineGeometry.Box box, BlockFace face, double u, double v) {
    if (face == BlockFace.UP) return new double[] { mix(box.minX, box.maxX, u), mix(box.minZ, box.maxZ, v) };
    if (face == BlockFace.DOWN) return new double[] { mix(box.minX, box.maxX, u), mix(1d - box.maxZ, 1d - box.minZ, v) };
    if (face == BlockFace.EAST) return new double[] { mix(box.minZ, box.maxZ, u), mix(1d - box.maxY, 1d - box.minY, v) };
    if (face == BlockFace.WEST) return new double[] { mix(1d - box.maxZ, 1d - box.minZ, u), mix(1d - box.maxY, 1d - box.minY, v) };
    if (face == BlockFace.SOUTH) return new double[] { mix(1d - box.maxX, 1d - box.minX, u), mix(1d - box.maxY, 1d - box.minY, v) };
    return new double[] { mix(box.minX, box.maxX, u), mix(1d - box.maxY, 1d - box.minY, v) };
  }

  private static double mix(double start, double end, double amount) {
    return start * (1d - amount) + end * amount;
  }

  private static double[] rotateUv(double u, double v, double rotationDeg) {
    if (Math.abs(rotationDeg) < 0.0001d) return new double[] { u, v };
    double angle = Math.toRadians(rotationDeg);
    double dx = u - 0.5d;
    double dy = v - 0.5d;
    double rotatedU = 0.5d + Math.cos(angle) * dx + Math.sin(angle) * dy;
    double rotatedV = 0.5d - Math.sin(angle) * dx + Math.cos(angle) * dy;
    return new double[] { rotatedU - Math.floor(rotatedU), rotatedV - Math.floor(rotatedV) };
  }

  private static Point2 interpolate(Point2[] corners, double u, double v) {
    double topX = corners[0].x * (1d - u) + corners[1].x * u;
    double topY = corners[0].y * (1d - u) + corners[1].y * u;
    double bottomX = corners[3].x * (1d - u) + corners[2].x * u;
    double bottomY = corners[3].y * (1d - u) + corners[2].y * u;
    return new Point2(topX * (1d - v) + bottomX * v, topY * (1d - v) + bottomY * v);
  }

  private static Path2D.Double polygon(Point2[] points) {
    Path2D.Double result = new Path2D.Double();
    result.moveTo(points[0].x, points[0].y);
    for (int index = 1; index < points.length; index++) result.lineTo(points[index].x, points[index].y);
    result.closePath();
    return result;
  }

  private static void fill(Graphics2D graphics, Path2D.Double shape, Color color) {
    graphics.setColor(color);
    graphics.fill(shape);
  }

  private static Color shade(Color source, String lighting, BlockFace face) {
    double factor;
    if ("vanilla_standard".equals(lighting)) {
      factor = face == BlockFace.UP ? 0.93d : face == BlockFace.EAST || face == BlockFace.WEST ? 0.72d : 0.61d;
    } else if ("simulated".equals(lighting)) {
      factor = face == BlockFace.UP ? 0.92d : face == BlockFace.EAST || face == BlockFace.WEST ? 0.79d : 0.67d;
    } else {
      factor = face == BlockFace.UP ? 1d : face == BlockFace.EAST || face == BlockFace.WEST ? 0.89d : 0.80d;
    }
    return new Color(
      Math.min(255, Math.max(0, (int) Math.round(source.getRed() * factor))),
      Math.min(255, Math.max(0, (int) Math.round(source.getGreen() * factor))),
      Math.min(255, Math.max(0, (int) Math.round(source.getBlue() * factor))),
      source.getAlpha()
    );
  }

  private static String fileName(String rawPrefix, String projection, ViewSpec view, int width, int height) {
    String prefix = rawPrefix == null ? "litematic" : rawPrefix.trim();
    prefix = prefix.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").replaceAll("^_+|_+$", "");
    if (prefix.isEmpty()) prefix = "litematic";
    return prefix + "__" + projection + "_from_" + view.id + "__" + width + "x" + height + ".png";
  }

  private static final class RenderJob {
    final Path litematic;
    final List<Path> resourcePacks;
    final String projection;
    final double elevation;
    final String background;
    final String lighting;
    final int width;
    final int height;
    final Path outputDirectory;
    final String namingPrefix;
    final List<ViewSpec> views;
    final boolean parallelDirections;

    RenderJob(Path litematic, List<Path> resourcePacks, String projection, double elevation, String background, String lighting, int width, int height, Path outputDirectory, String namingPrefix, List<ViewSpec> views, boolean parallelDirections) {
      this.litematic = litematic;
      this.resourcePacks = resourcePacks;
      this.projection = projection;
      this.elevation = elevation;
      this.background = background;
      this.lighting = lighting;
      this.width = width;
      this.height = height;
      this.outputDirectory = outputDirectory;
      this.namingPrefix = namingPrefix;
      this.views = views;
      this.parallelDirections = parallelDirections;
    }

    static RenderJob parse(Map<String, Object> root) {
      Map<String, Object> source = WorkerJson.object(root.get("source"));
      Map<String, Object> resources = WorkerJson.object(root.get("resources"));
      Map<String, Object> camera = WorkerJson.object(root.get("camera"));
      Map<String, Object> output = WorkerJson.object(root.get("output"));
      String rawLitematic = WorkerJson.string(source.get("litematicPath")).trim();
      if (rawLitematic.isEmpty()) throw new IllegalArgumentException("渲染任务未提供 .litematic 路径。");
      Path litematic = Paths.get(rawLitematic).toAbsolutePath().normalize();
      if (!Files.isRegularFile(litematic)) throw new IllegalArgumentException("找不到 .litematic 文件：" + litematic);
      ArrayList<Path> packs = new ArrayList<Path>();
      for (Object value : WorkerJson.array(resources.get("selectedResourcePacks"))) {
        String raw = WorkerJson.string(value).trim();
        if (!raw.isEmpty()) {
          Path pack = Paths.get(raw).toAbsolutePath().normalize();
          if (!Files.exists(pack)) throw new IllegalArgumentException("找不到所选资源包：" + pack);
          packs.add(pack);
        }
      }
      String requestedProjection = WorkerJson.string(camera.get("projection"));
      if (requestedProjection.isEmpty()) {
        Object projections = camera.get("projections");
        if (projections instanceof List && !((List<?>) projections).isEmpty()) {
          requestedProjection = WorkerJson.string(((List<?>) projections).get(0));
        }
      }
      String projection = "bottom_orthographic".equals(requestedProjection)
        ? "bottom_orthographic"
        : "perspective".equals(requestedProjection) ? "perspective" : "orthographic";
      double baseAzimuth = normalAngle(WorkerJson.decimal(camera.get("baseAzimuthDeg"), 45d));
      double elevation = WorkerJson.decimal(camera.get("elevationDeg"), 35.264d);
      if (elevation < 0d || elevation >= 90d) throw new IllegalArgumentException("俯角必须大于等于 0° 且小于 90°。");
      ArrayList<ViewSpec> views = new ArrayList<ViewSpec>();
      for (Object offset : WorkerJson.array(camera.get("selectedOffsetsDeg"))) {
        double angle = normalAngle(baseAzimuth + WorkerJson.decimal(offset, 0d));
        boolean duplicate = false;
        for (ViewSpec view : views) if (Math.abs(view.azimuth - angle) < 0.0001d) duplicate = true;
        if (!duplicate) views.add(new ViewSpec(angle));
      }
      if (views.isEmpty()) throw new IllegalArgumentException("请至少选择一个相对四向。");
      int width = WorkerJson.integer(output.get("width"), 0);
      int height = WorkerJson.integer(output.get("height"), 0);
      if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IllegalArgumentException("PNG 宽高必须在 1 到 16384 像素之间。");
      String rawOutput = WorkerJson.string(output.get("directory")).trim();
      if (rawOutput.isEmpty()) throw new IllegalArgumentException("渲染任务未提供 PNG 输出文件夹。");
      Path outputDirectory = Paths.get(rawOutput).toAbsolutePath().normalize();
      String background = "white".equals(WorkerJson.string(output.get("background"))) ? "white" : "transparent";
      Map<String, Object> execution = WorkerJson.object(root.get("execution"));
      String lighting = WorkerJson.string(execution.get("lighting"));
      if (!"technical_fullbright".equals(lighting) && !"vanilla_standard".equals(lighting) && !"simulated".equals(lighting)) lighting = "technical_fullbright";
      String namingPrefix = WorkerJson.string(output.get("namingPrefix"));
      boolean parallelDirections = "parallel".equals(WorkerJson.string(execution.get("directionSchedule")));
      return new RenderJob(litematic, packs, projection, elevation, background, lighting, width, height, outputDirectory, namingPrefix, views, parallelDirections);
    }

    private static double normalAngle(double value) {
      double result = value % 360d;
      return result < 0d ? result + 360d : result;
    }
  }

  private static final class ViewSpec {
    final double azimuth;
    final String id;
    final String label;

    ViewSpec(double azimuth) {
      this.azimuth = azimuth;
      int rounded = (int) Math.round(azimuth);
      if (close(azimuth, 45d)) {
        id = "posx_posz";
        label = "+X +Z";
      } else if (close(azimuth, 135d)) {
        id = "negx_posz";
        label = "−X +Z";
      } else if (close(azimuth, 225d)) {
        id = "negx_negz";
        label = "−X −Z";
      } else if (close(azimuth, 315d)) {
        id = "posx_negz";
        label = "+X −Z";
      } else {
        String text = String.format(Locale.ROOT, "%.3f", azimuth).replaceAll("0+$", "").replaceAll("\\.$", "");
        id = "custom_" + text.replace('.', '_');
        label = text + "°";
      }
    }

    private static boolean close(double left, double right) {
      return Math.abs(left - right) < 0.0005d;
    }
  }

  private static final class Camera {
    final boolean perspective;
    final Point3 target;
    final Point3 forward;
    final Point3 right;
    final Point3 up;
    final Point3 position;
    final double scale;
    final double offsetX;
    final double offsetY;
    final double outX;
    final double outZ;
    final boolean bottom;

    Camera(boolean perspective, boolean bottom, Point3 target, Point3 forward, Point3 right, Point3 up, Point3 position, double scale, double offsetX, double offsetY) {
      this.perspective = perspective;
      this.bottom = bottom;
      this.target = target;
      this.forward = forward;
      this.right = right;
      this.up = up;
      this.position = position;
      this.scale = scale;
      this.offsetX = offsetX;
      this.offsetY = offsetY;
      this.outX = -forward.x;
      this.outZ = -forward.z;
    }

    static Camera create(LitematicModel model, RenderJob job, double azimuth) {
      Point3 target = new Point3((model.frameMinX + model.frameMaxX + 1d) / 2d, (model.frameMinY + model.frameMaxY + 1d) / 2d, (model.frameMinZ + model.frameMaxZ + 1d) / 2d);
      double azimuthRad = Math.toRadians(azimuth);
      boolean bottom = "bottom_orthographic".equals(job.projection);
      if (bottom) {
        Point3 forward = new Point3(0d, 1d, 0d);
        Point3 right = new Point3(Math.cos(azimuthRad), 0d, -Math.sin(azimuthRad));
        Point3 up = new Point3(Math.sin(azimuthRad), 0d, Math.cos(azimuthRad));
        Point3 position = add(target, scale(forward, -(Math.sqrt(model.frameWidth() * (double) model.frameWidth() + model.frameHeight() * (double) model.frameHeight() + model.frameDepth() * (double) model.frameDepth()) * 2.6d + 8d)));
        double[] range = new double[] { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
        if (!model.framingBlocks.isEmpty()) {
          for (LitematicModel.Block block : model.framingBlocks) {
            for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 1; z++) {
              include(range, block.x + x, block.y + y, block.z + z, target, position, forward, right, up, false);
            }
          }
        } else {
          include(range, model.frameMinX, model.frameMinY, model.frameMinZ, target, position, forward, right, up, false);
          include(range, model.frameMaxX + 1d, model.frameMaxY + 1d, model.frameMaxZ + 1d, target, position, forward, right, up, false);
        }
        double margin = Math.max(16d, Math.min(job.width, job.height) * 0.034d);
        double rangeX = Math.max(0.0001d, range[1] - range[0]);
        double rangeY = Math.max(0.0001d, range[3] - range[2]);
        double scale = Math.min((job.width - margin * 2d) / rangeX, (job.height - margin * 2d) / rangeY);
        double offsetX = (job.width - rangeX * scale) / 2d - range[0] * scale;
        double offsetY = (job.height - rangeY * scale) / 2d + range[3] * scale;
        return new Camera(false, true, target, forward, right, up, position, scale, offsetX, offsetY);
      }
      double elevationRad = Math.toRadians(job.elevation);
      Point3 forward = new Point3(-Math.cos(elevationRad) * Math.cos(azimuthRad), -Math.sin(elevationRad), -Math.cos(elevationRad) * Math.sin(azimuthRad));
      Point3 right = normalize(cross(forward, new Point3(0d, 1d, 0d)));
      Point3 up = cross(right, forward);
      double diagonal = Math.sqrt(model.frameWidth() * (double) model.frameWidth() + model.frameHeight() * (double) model.frameHeight() + model.frameDepth() * (double) model.frameDepth());
      boolean perspective = "perspective".equals(job.projection);
      Point3 position = add(target, scale(forward, -(diagonal * 2.6d + 8d)));
      double[] range = new double[] { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
      if (!model.framingBlocks.isEmpty()) {
        for (LitematicModel.Block block : model.framingBlocks) {
          for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
              for (int z = 0; z <= 1; z++) {
                include(range, block.x + x, block.y + y, block.z + z, target, position, forward, right, up, perspective);
              }
            }
          }
        }
      } else {
        for (LitematicModel.Entity entity : model.entities) {
          double baseY = entity.renderBaseY();
          include(range, entity.x - 0.5d, baseY, entity.z - 0.5d, target, position, forward, right, up, perspective);
          include(range, entity.x + 0.5d, baseY + (entity.surfaceMounted() ? 1.05d : 1.55d), entity.z + 0.5d, target, position, forward, right, up, perspective);
        }
      }
      if (!Double.isFinite(range[0])) {
        include(range, model.frameMinX, model.frameMinY, model.frameMinZ, target, position, forward, right, up, perspective);
        include(range, model.frameMaxX + 1d, model.frameMaxY + 1d, model.frameMaxZ + 1d, target, position, forward, right, up, perspective);
      }
      double margin = Math.max(16d, Math.min(job.width, job.height) * 0.034d);
      double rangeX = Math.max(0.0001d, range[1] - range[0]);
      double rangeY = Math.max(0.0001d, range[3] - range[2]);
      double scale = Math.min((job.width - margin * 2d) / rangeX, (job.height - margin * 2d) / rangeY);
      double offsetX = (job.width - rangeX * scale) / 2d - range[0] * scale;
      double offsetY = (job.height - rangeY * scale) / 2d + range[3] * scale;
      return new Camera(perspective, false, target, forward, right, up, position, scale, offsetX, offsetY);
    }

    Point2 project(Point3 point) {
      double[] raw = raw(point, target, position, forward, right, up, perspective);
      return new Point2(offsetX + raw[0] * scale, offsetY - raw[1] * scale);
    }

    double depth(double x, double y, double z) {
      return dot(new Point3(x - target.x, y - target.y, z - target.z), forward);
    }

    private static void include(double[] range, double x, double y, double z, Point3 target, Point3 position, Point3 forward, Point3 right, Point3 up, boolean perspective) {
      double relativeX = x - (perspective ? position.x : target.x);
      double relativeY = y - (perspective ? position.y : target.y);
      double relativeZ = z - (perspective ? position.z : target.z);
      double depth = perspective ? Math.max(0.0001d, relativeX * forward.x + relativeY * forward.y + relativeZ * forward.z) : 1d;
      double horizontal = (relativeX * right.x + relativeY * right.y + relativeZ * right.z) / depth;
      double vertical = (relativeX * up.x + relativeY * up.y + relativeZ * up.z) / depth;
      range[0] = Math.min(range[0], horizontal);
      range[1] = Math.max(range[1], horizontal);
      range[2] = Math.min(range[2], vertical);
      range[3] = Math.max(range[3], vertical);
    }

    private static double[] raw(Point3 point, Point3 target, Point3 position, Point3 forward, Point3 right, Point3 up, boolean perspective) {
      Point3 relative = perspective ? new Point3(point.x - position.x, point.y - position.y, point.z - position.z) : new Point3(point.x - target.x, point.y - target.y, point.z - target.z);
      double depth = perspective ? Math.max(0.0001d, dot(relative, forward)) : 1d;
      return new double[] { dot(relative, right) / depth, dot(relative, up) / depth };
    }
  }

  private static final class DepthPart {
    final LitematicModel.Block block;
    final MachineGeometry.Shape shape;
    final MachineGeometry.Part part;
    final MachineGeometry.Box world;
    final double depth;
    final boolean entity;

    DepthPart(LitematicModel.Block block, MachineGeometry.Shape shape, MachineGeometry.Part part, MachineGeometry.Box world, double depth, boolean entity) {
      this.block = block;
      this.shape = shape;
      this.part = part;
      this.world = world;
      this.depth = depth;
      this.entity = entity;
    }
  }

  private static final class Point3 {
    final double x;
    final double y;
    final double z;

    Point3(double x, double y, double z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  private static final class Point2 {
    final double x;
    final double y;

    Point2(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  private static final class ViewProgress implements Progress {
    private final Progress delegate;
    private final double start;
    private final double span;
    private final String label;

    ViewProgress(Progress delegate, double start, double span, String label) {
      this.delegate = delegate;
      this.start = start;
      this.span = span;
      this.label = label;
    }

    public void report(String stage, String message, double fraction) {
      delegate.report(stage, message, Math.min(0.96d, start + span * Math.max(0d, Math.min(1d, fraction))));
    }
  }

  private static Point3 cross(Point3 left, Point3 right) {
    return new Point3(left.y * right.z - left.z * right.y, left.z * right.x - left.x * right.z, left.x * right.y - left.y * right.x);
  }

  private static Point3 normalize(Point3 point) {
    double length = Math.sqrt(point.x * point.x + point.y * point.y + point.z * point.z);
    if (length < 0.000001d) throw new IllegalArgumentException("相机俯角不能为 90°。");
    return new Point3(point.x / length, point.y / length, point.z / length);
  }

  private static Point3 add(Point3 left, Point3 right) {
    return new Point3(left.x + right.x, left.y + right.y, left.z + right.z);
  }

  private static Point3 scale(Point3 point, double amount) {
    return new Point3(point.x * amount, point.y * amount, point.z * amount);
  }

  private static double dot(Point3 left, Point3 right) {
    return left.x * right.x + left.y * right.y + left.z * right.z;
  }
}

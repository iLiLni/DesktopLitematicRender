package studio.litematicrender.worker;

enum BlockFace {
  UP("up", 0, 1, 0),
  DOWN("down", 0, -1, 0),
  EAST("east", 1, 0, 0),
  WEST("west", -1, 0, 0),
  SOUTH("south", 0, 0, 1),
  NORTH("north", 0, 0, -1);

  final String id;
  final int dx;
  final int dy;
  final int dz;

  BlockFace(String id, int dx, int dy, int dz) {
    this.id = id;
    this.dx = dx;
    this.dy = dy;
    this.dz = dz;
  }

  BlockFace opposite() {
    switch (this) {
      case UP: return DOWN;
      case DOWN: return UP;
      case EAST: return WEST;
      case WEST: return EAST;
      case SOUTH: return NORTH;
      default: return SOUTH;
    }
  }

  static BlockFace fromProperty(Object value, BlockFace fallback) {
    String raw = value == null ? "" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
    for (BlockFace face : values()) if (face.id.equals(raw)) return face;
    return fallback;
  }
}

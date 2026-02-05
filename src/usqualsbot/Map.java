package usqualsbot;

import static usqualsbot.Comms.encodeLocation;
import static usqualsbot.Globals.*;

import battlecode.common.*;

public class Map {
    static final int VISITED_BLOCK_DIMENSION = 3;

    static enum Tile {
        WALL,
        MINE,
        DIRT,
        EMPTY
    }

    static enum Symmetry {
        UNKNOWN,
        FLIP_X,
        FLIP_Y,
        ROTATE
    }

    static Tile[][] map;
    static int mapWidth;
    static int mapHeight;
    static boolean[][] visited;

    static Symmetry symmetry = Symmetry.UNKNOWN;
    static Symmetry prevSymmetry = Symmetry.UNKNOWN;
    static boolean canFlipX = true;
    static boolean canFlipY = true;
    static boolean canRotate = true;

    static MapLocation[] explorationGoals = new MapLocation[9];

    static void initMap(int width, int height) {
        map = new Tile[width][height];
        mapWidth = width;
        mapHeight = height;
        visited = new boolean[(width + VISITED_BLOCK_DIMENSION - 1)
                / VISITED_BLOCK_DIMENSION][(height + VISITED_BLOCK_DIMENSION - 1) / VISITED_BLOCK_DIMENSION];

        // Initialize 9 exploration goals
        int midX = width / 2;
        int midY = height / 2;
        explorationGoals[0] = new MapLocation(0, 0); // Bottom-left corner
        explorationGoals[1] = new MapLocation(width - 1, 0); // Bottom-right corner
        explorationGoals[2] = new MapLocation(0, height - 1); // Top-left corner
        explorationGoals[3] = new MapLocation(width - 1, height - 1); // Top-right corner
        explorationGoals[4] = new MapLocation(midX, 0); // Bottom edge midpoint
        explorationGoals[5] = new MapLocation(midX, height - 1); // Top edge midpoint
        explorationGoals[6] = new MapLocation(0, midY); // Left edge midpoint
        explorationGoals[7] = new MapLocation(width - 1, midY); // Right edge midpoint
        explorationGoals[8] = new MapLocation(midX, midY); // Center
    }

    static Tile get(int x, int y) {
        return map[x][y];
    }

    static Tile get(MapLocation loc) {
        return map[loc.x][loc.y];
    }

    static void markVisited(int x, int y) {
        visited[x / VISITED_BLOCK_DIMENSION][y / VISITED_BLOCK_DIMENSION] = true;

    }

    static boolean isVisited(int x, int y) {
        return visited[x / VISITED_BLOCK_DIMENSION][y / VISITED_BLOCK_DIMENSION];
    }

    static boolean isVisited(MapLocation loc) {
        return isVisited(loc.x, loc.y);
    }

    static MapLocation getSymmetricLocation(MapLocation loc, Symmetry symmetryType) {
        switch (symmetryType) {
            case FLIP_X:
                return new MapLocation(mapWidth - 1 - loc.x, loc.y);
            case FLIP_Y:
                return new MapLocation(loc.x, mapHeight - 1 - loc.y);
            case ROTATE:
                return new MapLocation(mapWidth - 1 - loc.x, mapHeight - 1 - loc.y);
            default:
                return loc;
        }
    }

    static boolean tilesCompatible(Tile tile1, Tile tile2) {
        if (tile1 == null || tile2 == null) {
            return true;
        }
        if (tile1 == Tile.WALL || tile2 == Tile.WALL || tile1 == Tile.MINE || tile2 == Tile.MINE) {
            return tile1 == tile2;
        }
        return true;
    }

    static void checkTileSymmetry(int x, int y, Tile newTile) throws GameActionException {
        if (symmetry != Symmetry.UNKNOWN) {
            return;
        }

        if (canFlipX) {
            if (!tilesCompatible(newTile, map[mapWidth - 1 - x][y])) {
                canFlipX = false;
            }
        }

        if (canFlipY) {
            if (!tilesCompatible(newTile, map[x][mapHeight - 1 - y])) {
                canFlipY = false;
            }
        }

        if (canRotate) {
            if (!tilesCompatible(newTile, map[mapWidth - 1 - x][mapHeight - 1 - y])) {
                canRotate = false;
            }
        }
    }

    static void setTile(Tile tile, MapLocation loc) throws GameActionException {
        if (map[loc.x][loc.y] == null) {
            checkTileSymmetry(loc.x, loc.y, tile);
        }
        map[loc.x][loc.y] = tile;
        markVisited(loc.x, loc.y);
    }

    static void setTile(MapInfo tile) throws GameActionException {
        Tile newTile;

        if (tile.isWall()) {
            newTile = Tile.WALL;
        } else if (tile.hasCheeseMine()) {
            newTile = Tile.MINE;
            char encoded = encodeLocation(tile.getMapLocation());
            if (!mineLocations.add(encoded)) {
                if (symmetry != Symmetry.UNKNOWN) {
                    MapLocation symLoc = getSymmetricLocation(tile.getMapLocation(), symmetry);
                    mineLocations.add(encodeLocation(symLoc));
                }
                if (rc.getType() == UnitType.RAT_KING) {
                    Comms.writeMineLocation(tile.getMapLocation());
                }
            }
            if (chosenMine != null && encoded == encodeLocation(chosenMine)) {
                if (!arrivedAtChosenMine) {
                    lastSeenCheese = rc.getRoundNum();
                }
                arrivedAtChosenMine = true;
            }

        } else if (tile.isDirt()) {
            newTile = Tile.DIRT;
        } else {
            newTile = Tile.EMPTY;
        }

        setTile(newTile, tile.getMapLocation());

        /*
         * if (symmetry != Symmetry.UNKNOWN) {
         * if (symmetry == Symmetry.FLIP_X) {
         * map[mapWidth - 1 - tile.getMapLocation().x][tile.getMapLocation().y] =
         * newTile;
         * } else if (symmetry == Symmetry.FLIP_Y) {
         * map[tile.getMapLocation().x][mapHeight - 1 - tile.getMapLocation().y] =
         * newTile;
         * } else if (symmetry == Symmetry.ROTATE) {
         * map[mapWidth - 1 - tile.getMapLocation().x][mapHeight - 1 -
         * tile.getMapLocation().y] = newTile;
         * }
         * }
         */
    }

    static void updateSymmetry() throws GameActionException {
        if (symmetry != Symmetry.UNKNOWN) {
            return;
        }

        if (canFlipX && !canFlipY && !canRotate) {
            symmetry = Symmetry.FLIP_X;
        } else if (!canFlipX && canFlipY && !canRotate) {
            symmetry = Symmetry.FLIP_Y;
        } else if (!canFlipX && !canFlipY && canRotate) {
            symmetry = Symmetry.ROTATE;
        } else {
            return;
        }
        if (rc.getType() == UnitType.RAT_KING) {
            Comms.writeSymmetry(symmetry);
        }
        addSymmetricalMines();
    }

    static void addSymmetricalMines() throws GameActionException {
        if (symmetry == Symmetry.UNKNOWN)
            return;

        FastSet newMines = new FastSet();
        for (char locInt : mineLocations) {
            MapLocation loc = Comms.decodeLocation(locInt);
            MapLocation symLoc = getSymmetricLocation(loc, symmetry);
            char symLocInt = encodeLocation(symLoc);
            newMines.add(symLocInt);
        }
        mineLocations.addAll(newMines);
    }

    static MapLocation getUnexploredLocation() throws GameActionException {
        if (rc.getRoundNum() < 200 || rng.nextBoolean()) {
            return getRemoteLocation();
        } else {
            return getUnvisitedLocation();
        }
    }

    /**
     * Choose an exploration target from the 9 predefined goal locations.
     * Filters out goals that are within 20 radius squared of any king.
     * Returns a random valid goal, or a random location if all goals are near
     * kings.
     */
    static MapLocation getRemoteLocation() throws GameActionException {
        // Find valid goals (not near any king)
        int validCount = 0;
        int[] validIndices = new int[9];

        for (int i = 0; i < 9; i++) {
            MapLocation goal = explorationGoals[i];
            boolean tooCloseToKing = false;

            for (int k = 0; k < 5; k++) {
                if (currentKingLocs[k] != null && goal.distanceSquaredTo(currentKingLocs[k]) <= 20) {
                    tooCloseToKing = true;
                    break;
                }
            }

            if (!tooCloseToKing) {
                validIndices[validCount] = i;
                validCount++;
            }
        }

        // Return a random valid goal, or random location if none valid
        if (validCount > 0) {
            int chosenIndex = validIndices[rng.nextInt(validCount)];
            return explorationGoals[chosenIndex];
        }

        // Fallback: all goals are near kings, return random location
        int randX = rng.nextInt(mapWidth);
        int randY = rng.nextInt(mapHeight);
        return new MapLocation(randX, randY);
    }

    // Direction offsets for blocks: NORTH, NORTHEAST, EAST, SOUTHEAST, SOUTH, SOUTHWEST, WEST, NORTHWEST
    static final int[] DIR_DX = {0, 1, 1, 1, 0, -1, -1, -1};
    static final int[] DIR_DY = {1, 1, 0, -1, -1, -1, 0, 1};

    static MapLocation getUnvisitedLocation() {
        int currentBlockX = myLoc.x / VISITED_BLOCK_DIMENSION;
        int currentBlockY = myLoc.y / VISITED_BLOCK_DIMENSION;

        int visitedWidth = (mapWidth + VISITED_BLOCK_DIMENSION - 1) / VISITED_BLOCK_DIMENSION;
        int visitedHeight = (mapHeight + VISITED_BLOCK_DIMENSION - 1) / VISITED_BLOCK_DIMENSION;

        // Pick a random starting direction (0-7)
        int startDir = rng.nextInt(8);

        // Check all 8 directions starting from startDir
        for (int i = 0; i < 8; i++) {
            int dirIndex = (startDir + i) % 8;
            int blockX = currentBlockX + DIR_DX[dirIndex];
            int blockY = currentBlockY + DIR_DY[dirIndex];

            // Check bounds
            if (blockX < 0 || blockX >= visitedWidth) continue;
            if (blockY < 0 || blockY >= visitedHeight) continue;

            // Check if unexplored
            if (!visited[blockX][blockY]) {
                // Return center tile of this block
                int tileX = blockX * VISITED_BLOCK_DIMENSION + VISITED_BLOCK_DIMENSION / 2;
                int tileY = blockY * VISITED_BLOCK_DIMENSION + VISITED_BLOCK_DIMENSION / 2;
                // Clamp to map bounds
                tileX = Math.min(tileX, mapWidth - 1);
                tileY = Math.min(tileY, mapHeight - 1);
                return new MapLocation(tileX, tileY);
            }
        }

        // All adjacent blocks visited, return completely random location
        int randX = rng.nextInt(mapWidth);
        int randY = rng.nextInt(mapHeight);
        return new MapLocation(randX, randY);
    }
}

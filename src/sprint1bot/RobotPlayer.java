package sprint1bot;

import battlecode.common.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class RobotPlayer {
    static final Direction[] DIRS = new Direction[] {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST
    };

    static enum Symmetry {
        UNKNOWN,
        FLIP_X,
        FLIP_Y,
        ROTATE
    }

    static enum State {
        NONE,
        FIND_KING,
        EXPLORE,
        ATTACK,
        RUSH,
        EVADE,
        COLLECT,
        RETURN
    }

    static enum Tile {
        WALL,
        MINE,
        DIRT,
        EMPTY
    }

    // Global array indices for shared communication
    static final int SHARED_SYMMETRY_INDEX = 0;
    static final int SHARED_STARTING_KING_POSITION_INDEX = 1;
    static final int SHARED_CURRENT_KING_POSITION_INDEX = 2; // Base index, king i is at index 1+i, up to 5 kings
    static final int SHARED_MINE_COUNT_INDEX = 7; // Number of mines stored in shared array
    static final int SHARED_MINES_START_INDEX = 8; // Mine locations start here, up to index 63

    static final int MAX_ALIVE_RATS = 18;
    static final int CHEESE_EMERGENCY_THRESHOLD = 600; // Moderate threshold for cheese collection
    static final int CHEESE_CRITICAL_THRESHOLD = 150; // Only truly critical - very rare
    static final int EVADE_TIMEOUT_TURNS = 8;
    static final int VISITED_BLOCK_DIMENSION = 3;
    static final int MAX_CHEESE_CARRIED = 200;
    static final int MAX_CHEESE_CARRIED_EMERGENCY = 100;

    static boolean shouldCollectCheese() {
        if (rc.getRawCheese() >= MAX_CHEESE_CARRIED) {
            return false;
        }
        if (rc.getRawCheese() == MAX_CHEESE_CARRIED_EMERGENCY && rc.getGlobalCheese() <= CHEESE_EMERGENCY_THRESHOLD) {
            return false;
        }
        return true;
    }

    // Rat king emergency spawn toggle (for cat - spawn every other turn)
    static boolean catSpawnToggle = false;

    // Squeak message types (first byte of 4-byte squeak)
    static final int SQUEAK_TYPE_ENEMY_KING = 0;
    static final int SQUEAK_TYPE_CHEESE_MINE = 1;
    static final int SQUEAK_TYPE_SYMMETRY = 2;

    static Random rng;

    static RobotController rc;

    static Team myTeam;
    static Team enemyTeam;

    static State currentState = State.NONE;

    static int mapWidth;
    static int mapHeight;

    static MapLocation myLoc;
    static MapLocation prevLoc;

    static Tile[][] map;

    // Visited array - tracks which NxN blocks we've explored (N =
    // VISITED_BLOCK_DIMENSION)
    // Dimensions: [ceil(height/N)][ceil(width/N)], indexed by loc.y/N and loc.x/N
    static boolean[][] visited;
    static boolean visitedAll = false; // True if no unexplored tiles remain

    // Track symmetry
    static Symmetry determinedSymmetry = Symmetry.UNKNOWN;
    static boolean canFlipX = true;
    static boolean canFlipY = true;
    static boolean canRotate = true;

    static MapLocation destination;

    // Explore mode stuck detection
    static int exploreSmallestDistSq = Integer.MAX_VALUE; // Smallest distance squared to destination
    static int exploreTurnsWithoutProgress = 0; // Turns since distance improved
    static final int EXPLORE_STUCK_THRESHOLD = 10; // Choose new destination after this many turns without progress

    // Enemy rat king location (if known)
    static MapLocation enemyRatKingLoc = null;
    static int enemyRatKingLastSeen = -1;

    // Cat evasion tracking
    static MapLocation catLastSeenLoc = null;
    static int catLastSeenRound = -1;
    static int lastHealth = -1; // Track health to detect sudden drops (cat scratch from behind)
    static int evadeStartRound = -1; // When we entered EVADE state

    static int turnsSinceLastCheck = 0; // For periodic look-back while evading

    static MapLocation ratKingLoc = null; // Our rat king's location for returning cheese

    // King index for this rat king (0-4 valid, 5 means no slot available)
    static int myKingIndex = -1;

    // Attack target
    static RobotInfo attackTarget = null;

    // Mine locations this rat has discovered (encoded positions)
    static Set<Integer> mineLocations = new HashSet<>();
    static int prevNumberOfMines = 0; // For tracking shared array mine count changes

    // COLLECT mode: track mines we've already checked (no cheese found there)
    static Set<Integer> checkedMines = new HashSet<>();
    static MapLocation currentMineTarget = null; // Current mine we're navigating to

    // Collector role tracking (only some rats should collect)
    static boolean isCollectorRole = false; // True if this rat should collect when cheese is low
    static boolean hasDecidedCollectorRole = false; // True once we've rolled for collector role
    static boolean wasCheeseAboveThreshold = true; // Track cheese threshold transitions

    // Scout role - prioritizes exploration over rushing to discover mines
    static boolean isScoutRole = false; // True if this rat should explore to find mines
    static final int SCOUT_PERCENTAGE = 20; // 20% of rats are scouts

    // Assigned mine index for collection (to prevent crowding at same mine)
    static int assignedMineIndex = -1; // Which mine this rat is assigned to (-1 = none)

    // Possible enemy spawn locations (3 symmetric possibilities)
    static MapLocation[] possibleEnemySpawns = new MapLocation[3];
    static int currentTargetIndex = -1; // Which spawn we're currently heading to (-1 = unassigned)

    // Track which spawns we've visited/checked
    static boolean[] spawnChecked = new boolean[3];

    // Flag to track if we've assigned our initial target
    static boolean hasAssignedTarget = false;

    // ------------------------------
    // Enemy Spawn Targeting
    // ------------------------------

    /**
     * Initialize the 3 possible enemy spawn locations based on rat king's starting
     * position.
     * Returns true if initialization was successful, false if king position not yet
     * available.
     */
    static void initializePossibleSpawns() throws GameActionException {
        MapLocation kingStartLoc = decodeLocation(rc.readSharedArray(SHARED_STARTING_KING_POSITION_INDEX));
        possibleEnemySpawns[0] = getSymmetricLocation(kingStartLoc, Symmetry.FLIP_X);
        possibleEnemySpawns[1] = getSymmetricLocation(kingStartLoc, Symmetry.FLIP_Y);
        possibleEnemySpawns[2] = getSymmetricLocation(kingStartLoc, Symmetry.ROTATE);
    }

    static void writeSymmetryToSharedArray(Symmetry symmetry) throws GameActionException {
        if (rc.getType() == UnitType.RAT_KING) {
            rc.writeSharedArray(SHARED_SYMMETRY_INDEX, symmetry.ordinal());
        }
    }

    /**
     * Check if two tiles are compatible under symmetry rules.
     * WALL must match WALL, MINE must match MINE.
     * EMPTY and DIRT are interchangeable (dirt can be added/removed).
     * UNKNOWN means we can't rule out compatibility yet.
     */
    static boolean tilesCompatible(Tile tile1, Tile tile2) {
        if (tile1 == null || tile2 == null) {
            return true;
        }
        if (tile1 == Tile.WALL || tile2 == Tile.WALL || tile1 == Tile.MINE || tile2 == Tile.MINE) {
            return tile1 == tile2;
        }
        return true;
    }

    /**
     * Check a newly observed tile against all possible symmetries.
     * If the symmetric tile has already been observed and is incompatible,
     * that symmetry type is ruled out.
     */
    static void checkTileSymmetry(MapLocation loc, Tile newTile) throws GameActionException {
        if (determinedSymmetry != Symmetry.UNKNOWN)
            return;

        // Check FLIP_X
        if (canFlipX) {
            MapLocation symLoc = getSymmetricLocation(loc, Symmetry.FLIP_X);
            Tile symTile = map[symLoc.x][symLoc.y];
            if (!tilesCompatible(newTile, symTile)) {
                canFlipX = false;
            }
        }

        // Check FLIP_Y
        if (canFlipY) {
            MapLocation symLoc = getSymmetricLocation(loc, Symmetry.FLIP_Y);
            Tile symTile = map[symLoc.x][symLoc.y];
            if (!tilesCompatible(newTile, symTile)) {
                canFlipY = false;
            }
        }

        // Check ROTATE
        if (canRotate) {
            MapLocation symLoc = getSymmetricLocation(loc, Symmetry.ROTATE);
            Tile symTile = map[symLoc.x][symLoc.y];
            if (!tilesCompatible(newTile, symTile)) {
                canRotate = false;
            }
        }
    }

    /**
     * Check if we can determine symmetry based on which are still possible.
     * If only one symmetry remains possible, set it as determined.
     */
    static void updateSymmetry() throws GameActionException {
        if (determinedSymmetry != Symmetry.UNKNOWN)
            return;

        if (canFlipX && !canFlipY && !canRotate) {
            determinedSymmetry = Symmetry.FLIP_X;
            currentTargetIndex = 0;
        } else if (!canFlipX && canFlipY && !canRotate) {
            determinedSymmetry = Symmetry.FLIP_Y;
            currentTargetIndex = 1;
        } else if (!canFlipX && !canFlipY && canRotate) {
            determinedSymmetry = Symmetry.ROTATE;
            currentTargetIndex = 2;
        } else {
            return;
        }
        if (rc.getType() == UnitType.RAT_KING) {
            writeSymmetryToSharedArray(determinedSymmetry);
            condenseSymmetricalMines();
        }
        updateMapWithSymmetry();
        destination = null;
    }

    /**
     * Try to determine map symmetry based on what we've seen.
     * Returns UNKNOWN if not yet determined, otherwise the determined symmetry
     * type.
     */
    static Symmetry tryDetermineSymmetry() {
        if (determinedSymmetry != Symmetry.UNKNOWN) {
            return determinedSymmetry;
        }

        if (canFlipX && !canFlipY && !canRotate) {
            return Symmetry.FLIP_X;
        } else if (!canFlipX && canFlipY && !canRotate) {
            return Symmetry.FLIP_Y;
        } else if (!canFlipX && !canFlipY && canRotate) {
            return Symmetry.ROTATE;
        }

        return Symmetry.UNKNOWN;
    }

    /**
     * Get the current spawn location target.
     * Does NOT change the target - only returns it.
     */
    static MapLocation getCurrentSpawnTarget() {
        if (determinedSymmetry != Symmetry.UNKNOWN) {
            currentTargetIndex = determinedSymmetry.ordinal() - 1;
            return possibleEnemySpawns[currentTargetIndex];
        }

        if (currentTargetIndex < 0) {
            currentTargetIndex = rng.nextInt(3);
        }

        return possibleEnemySpawns[currentTargetIndex];
    }

    /**
     * Move to the next unchecked spawn target.
     * Called only when current target has been confirmed as checked.
     */
    static void advanceToNextTarget() {
        if (determinedSymmetry != Symmetry.UNKNOWN) {
            return;
        }

        // Note: current spawn was already marked as checked by caller

        // Find the next unchecked spawn
        for (int i = 1; i <= 3; i++) {
            int idx = (currentTargetIndex + i) % 3;
            if (!spawnChecked[idx]) {
                currentTargetIndex = idx;
                destination = null; // Force destination update
                return;
            }
        }

        // All checked but no king found? Reset and try again
        spawnChecked[0] = false;
        spawnChecked[1] = false;
        spawnChecked[2] = false;
        currentTargetIndex = (currentTargetIndex + 1) % 3;
        destination = null; // Force destination update
    }

    /**
     * Update spawn checking based on current position.
     * Marks spawns as checked when we have actually SEEN the spawn tile (via
     * senseNearby).
     * Returns true if we should change our target.
     */
    static boolean updateSpawnChecking() throws GameActionException {
        if (currentTargetIndex >= 0 && !spawnChecked[currentTargetIndex]) {
            MapLocation targetSpawn = possibleEnemySpawns[currentTargetIndex];

            if (map[targetSpawn.x][targetSpawn.y] != null) {
                spawnChecked[currentTargetIndex] = true;
                advanceToNextTarget();
                return true;
            }
        }

        return false;
    }

    // ------------------------------
    // Sensing / exploration target
    // ------------------------------
    public static void senseNearby() throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        for (MapInfo tile : nearby) {
            MapLocation loc = tile.getMapLocation();
            Tile oldTile = map[loc.x][loc.y];
            Tile newTile;

            if (tile.isWall()) {
                newTile = Tile.WALL;
            } else if (tile.hasCheeseMine()) {
                newTile = Tile.MINE;
                mineLocations.add(encodeLocation(loc));
                if (currentState == State.EXPLORE && shouldCollectCheese()) {
                    currentState = State.COLLECT;
                }
            } else if (tile.isDirt()) {
                newTile = Tile.DIRT;
            } else {
                newTile = Tile.EMPTY;
            }

            if (oldTile == null) {
                checkTileSymmetry(loc, newTile);
            }

            map[loc.x][loc.y] = newTile;

            if (determinedSymmetry != Symmetry.UNKNOWN) {
                MapLocation symmetricLoc = getSymmetricLocation(loc, determinedSymmetry);
                if (map[symmetricLoc.x][symmetricLoc.y] == null) {
                    map[symmetricLoc.x][symmetricLoc.y] = newTile;
                }
            }

            visited[loc.y / VISITED_BLOCK_DIMENSION][loc.x / VISITED_BLOCK_DIMENSION] = true;

            // If in COLLECT mode, try to pick up any cheese we can see
            if (currentState == State.COLLECT || currentState == State.RETURN) {
                if (tile.getCheeseAmount() > 0) {
                    MapLocation cheeseLoc = tile.getMapLocation();
                    tryPickUpCheese(cheeseLoc);
                }
            }
        }

        if (rc.getType() == UnitType.BABY_RAT) {
            squeakToKingIfNearby();
        }
    }

    static void updateMapWithSymmetry() {
        if (determinedSymmetry == Symmetry.UNKNOWN) {
            return;
        }
        for (int x = 0; x < mapWidth; x++) {
            for (int y = 0; y < mapHeight; y++) {
                MapLocation loc = new MapLocation(x, y);
                Tile tile = map[x][y];
                if (tile != null) {
                    MapLocation symLoc = getSymmetricLocation(loc, determinedSymmetry);
                    if (map[symLoc.x][symLoc.y] == null) {
                        map[symLoc.x][symLoc.y] = tile;
                    }
                }
            }
        }
    }

    static void tryPickUpCheese(MapLocation loc) throws GameActionException {
        if (rc.canPickUpCheese(loc) && shouldCollectCheese()) {
            rc.pickUpCheese(loc);
        }
    }

    /**
     * If we can see an ally rat king, squeak important info.
     * Priority: symmetry (if known and not in shared array), then mines.
     */
    static void squeakToKingIfNearby() throws GameActionException {
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, myTeam);
        boolean allyKingNearby = false;
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.RAT_KING) {
                allyKingNearby = true;
                break;
            }
        }

        if (!allyKingNearby || currentState == State.EVADE) {
            return;
        }

        // Priority 1: Squeak symmetry if we know it and shared array doesn't have it
        if (determinedSymmetry != Symmetry.UNKNOWN && rc.readSharedArray(SHARED_SYMMETRY_INDEX) == 0) {
            rc.squeak(encodeSymmetrySqueak(determinedSymmetry.ordinal()));
            return;
        }

        // Priority 2: Squeak mines not in shared array
        int mineCount = rc.readSharedArray(SHARED_MINE_COUNT_INDEX);

        for (int encodedMine : mineLocations) {
            boolean inSharedArray = false;
            for (int i = 0; i < mineCount; i++) {
                int storedMine = rc.readSharedArray(SHARED_MINES_START_INDEX + i);
                if (storedMine == encodedMine) {
                    inSharedArray = true;
                    break;
                }
            }

            if (!inSharedArray) {
                rc.squeak(encodeSqueak(SQUEAK_TYPE_CHEESE_MINE, decodeLocation(encodedMine)));
                return;
            }
        }
    }

    /**
     * Encode a symmetry squeak message.
     * Format: [SQUEAK_TYPE_SYMMETRY (8 bits)][symmetry ordinal (24 bits)]
     */
    static int encodeSymmetrySqueak(int symmetryOrdinal) {
        return (SQUEAK_TYPE_SYMMETRY << 24) | symmetryOrdinal;
    }

    /**
     * Get the symmetry ordinal from an encoded symmetry squeak.
     */
    static int getSqueakSymmetryOrdinal(int squeak) {
        return squeak & 0xFFFFFF;
    }

    /**
     * Choose an exploration target by searching for unexplored NxN blocks
     * in expanding concentric rings from the robot's current position.
     * Returns a MapLocation within the chosen unexplored block.
     * If all blocks are visited, sets visitedAll=true and returns a random
     * location.
     */
    static MapLocation chooseExploreTarget() {
        // Find which block we're currently in
        int currentBlockX = myLoc.x / VISITED_BLOCK_DIMENSION;
        int currentBlockY = myLoc.y / VISITED_BLOCK_DIMENSION;

        int visitedWidth = (mapWidth + VISITED_BLOCK_DIMENSION - 1) / VISITED_BLOCK_DIMENSION;
        int visitedHeight = (mapHeight + VISITED_BLOCK_DIMENSION - 1) / VISITED_BLOCK_DIMENSION;
        int maxRing = Math.max(visitedWidth, visitedHeight);

        // Collect unexplored blocks at each ring distance
        // Search in expanding concentric rings
        for (int ring = 1; ring <= maxRing; ring++) {
            // Collect all unexplored blocks at this ring distance
            int unexploredCount = 0;
            int chosenBlockX = -1;
            int chosenBlockY = -1;

            // Check all blocks where max(|dx|, |dy|) == ring (the ring perimeter)
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    // Only consider blocks on the ring perimeter
                    if (Math.abs(dx) != ring && Math.abs(dy) != ring)
                        continue;

                    int blockX = currentBlockX + dx;
                    int blockY = currentBlockY + dy;

                    // Check bounds
                    if (blockX < 0 || blockX >= visitedWidth)
                        continue;
                    if (blockY < 0 || blockY >= visitedHeight)
                        continue;

                    // Check if unexplored
                    if (!visited[blockY][blockX]) {
                        unexploredCount++;
                        // Reservoir sampling: choose uniformly at random
                        if (rng.nextInt(unexploredCount) == 0) {
                            chosenBlockX = blockX;
                            chosenBlockY = blockY;
                        }
                    }
                }
            }

            // If we found unexplored blocks at this ring, return one
            if (unexploredCount > 0) {
                visitedAll = false;
                // Convert block coordinates back to tile coordinates (center of the block)
                int tileX = chosenBlockX * VISITED_BLOCK_DIMENSION + VISITED_BLOCK_DIMENSION / 2;
                int tileY = chosenBlockY * VISITED_BLOCK_DIMENSION + VISITED_BLOCK_DIMENSION / 2;
                // Clamp to map bounds
                tileX = Math.min(tileX, mapWidth - 1);
                tileY = Math.min(tileY, mapHeight - 1);
                return new MapLocation(tileX, tileY);
            }
        }

        // No unexplored blocks found - all visited
        visitedAll = true;

        // Choose a random tile anywhere on the map
        int randX = rng.nextInt(mapWidth);
        int randY = rng.nextInt(mapHeight);
        return new MapLocation(randX, randY);
    }

    // ------------------------------
    // Digging Logic
    // ------------------------------
    static boolean tryDigToward(MapLocation target) throws GameActionException {
        if (target == null)
            return false;

        Direction dir = myLoc.directionTo(target);
        if (dir == Direction.CENTER)
            return false;

        // Try the direct direction first, then adjacent directions
        Direction[] digsToTry = {
                dir,
                dir.rotateLeft(),
                dir.rotateRight()
        };

        for (Direction d : digsToTry) {
            MapLocation digLoc = myLoc.add(d);
            if (rc.onTheMap(digLoc) && canDigAt(digLoc)) {
                if (rc.canRemoveDirt(digLoc)) {
                    rc.removeDirt(digLoc);
                    return true;
                }
            }
        }

        return false;
    }

    static boolean canDigAt(MapLocation loc) {
        if (!rc.onTheMap(loc))
            return false;
        // Check if this location is known dirt
        return map[loc.x][loc.y] == Tile.DIRT;
    }

    static boolean tryDigAdjacent() throws GameActionException {
        // Try to dig any adjacent dirt that might be blocking us
        for (Direction d : DIRS) {
            MapLocation digLoc = myLoc.add(d);
            if (rc.onTheMap(digLoc) && canDigAt(digLoc)) {
                if (rc.canRemoveDirt(digLoc)) {
                    rc.removeDirt(digLoc);
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------
    // Enemy Detection and Targeting
    // ------------------------------
    static RobotInfo findBestTarget() throws GameActionException {
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        // Check if our king is nearby (prioritize enemies near our king)
        MapLocation nearestAllyKing = findNearestRatKing();

        // Also check if we can actually see our ally king (for adjacent enemy
        // prioritization)
        MapLocation visibleAllyKing = null;
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, myTeam);
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.RAT_KING) {
                visibleAllyKing = ally.getLocation();
                break;
            }
        }

        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);

        for (RobotInfo robot : nearbyEnemies) {
            int score = 0;
            MapLocation robotLoc = robot.getLocation();
            int distToMe = myLoc.distanceSquaredTo(robotLoc);

            // Check if it's an enemy rat king
            if (robot.getType() == UnitType.RAT_KING) {
                // Highest priority: enemy rat king
                score += 10000;

                enemyRatKingLoc = robotLoc;
                enemyRatKingLastSeen = rc.getRoundNum();
            } else if (robot.getType() == UnitType.CAT) {
                // Cats are lower priority than rat kings but higher than baby rats
                score += 5000;
            } else if (robot.getType() == UnitType.BABY_RAT) {
                score += 1000;
            }

            // HIGHEST PRIORITY (after enemy king): enemies adjacent to our visible ally
            // king
            // This ensures we defend our king by attacking threats directly next to it
            if (visibleAllyKing != null && robot.getType() == UnitType.BABY_RAT) {
                if (isLocationAdjacentToRatKing(robotLoc, visibleAllyKing)) {
                    score += 3000; // Very high bonus - below enemy king but above all else
                }
            }

            // Prioritize low HP enemies (easier kills)
            int hp = robot.getHealth();
            score += (200 - hp); // Lower HP = higher score

            // Prioritize enemies close to our king (defenders) - lower bonus than adjacent
            if (nearestAllyKing != null) {
                int distToOurKing = robotLoc.distanceSquaredTo(nearestAllyKing);
                if (distToOurKing <= 25) {
                    score += 500; // Bonus for threats near our king
                }
            }

            // Prioritize closer enemies
            score -= distToMe;

            if (score > bestScore) {
                bestScore = score;
                bestTarget = robot;
            }
        }

        return bestTarget;
    }

    /**
     * Get all locations adjacent to a rat king's center (for attacking)
     * Rat king occupies a 3x3 area, so we can attack any of the 8 tiles around its
     * center
     */
    static MapLocation[] getRatKingAttackLocations(MapLocation kingCenter) {
        MapLocation[] locs = new MapLocation[8];
        int i = 0;
        for (Direction d : DIRS) {
            locs[i++] = kingCenter.add(d);
        }
        return locs;
    }

    /**
     * Check if we are adjacent to any part of the enemy rat king
     */
    static boolean isAdjacentToRatKing(MapLocation kingCenter) {
        // Rat king is 3x3, check if we're adjacent to any part
        int dx = Math.abs(myLoc.x - kingCenter.x);
        int dy = Math.abs(myLoc.y - kingCenter.y);
        // We're adjacent if we're within 2 tiles in both directions but not inside the
        // king
        return dx <= 2 && dy <= 2 && !(dx <= 1 && dy <= 1);
    }

    /**
     * Check if a location is adjacent to any part of a rat king's 3x3 body.
     * The rat king occupies a 3x3 area centered at kingCenter.
     * A location is adjacent if it's within distance 2 in both x and y from the
     * center,
     * but not inside the 3x3 body (distance <= 1 in both x and y).
     */
    static boolean isLocationAdjacentToRatKing(MapLocation loc, MapLocation kingCenter) {
        int dx = Math.abs(loc.x - kingCenter.x);
        int dy = Math.abs(loc.y - kingCenter.y);
        // Adjacent if within 2 tiles in both directions but not inside the king's 3x3
        // body
        return dx <= 2 && dy <= 2 && !(dx <= 1 && dy <= 1);
    }

    /**
     * Get the best location to attack the rat king from our current position
     */
    static MapLocation getBestAttackLocationForKing(MapLocation kingCenter) {
        MapLocation[] attackLocs = getRatKingAttackLocations(kingCenter);
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapLocation loc : attackLocs) {
            if (rc.onTheMap(loc)) {
                int dist = myLoc.distanceSquaredTo(loc);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = loc;
                }
            }
        }
        return best;
    }

    /**
     * Get all 4 tiles of a cat's 2x2 body.
     * Cat's location is the tile with lowest x,y coordinates.
     * Cat occupies: (x,y), (x+1,y), (x,y+1), (x+1,y+1)
     */
    static MapLocation[] getCatBodyTiles(MapLocation catLoc) {
        return new MapLocation[] {
                catLoc,
                catLoc.translate(1, 0),
                catLoc.translate(0, 1),
                catLoc.translate(1, 1)
        };
    }

    /**
     * Get the best location to attack the cat from our current position.
     * Cat is 2x2, so we attack the closest tile of its body.
     */
    static MapLocation getBestAttackLocationForCat(MapLocation catLoc) {
        MapLocation[] catTiles = getCatBodyTiles(catLoc);
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapLocation tile : catTiles) {
            if (rc.onTheMap(tile)) {
                int dist = myLoc.distanceSquaredTo(tile);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = tile;
                }
            }
        }
        return best;
    }

    // ------------------------------
    // Ratnapping and Throwing Logic
    // ------------------------------

    /**
     * Find an enemy baby rat that can be ratnapped.
     * Prioritizes rats adjacent to our ally rat king, then by lowest HP.
     * Returns null if no ratnappable rat found.
     */
    static RobotInfo findRatnappableRat() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, enemyTeam);

        // Check if we can see our ally king
        MapLocation visibleAllyKing = null;
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, myTeam);
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.RAT_KING) {
                visibleAllyKing = ally.getLocation();
                break;
            }
        }

        RobotInfo bestRatnappable = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo robot : nearbyRobots) {
            if (!rc.canCarryRat(robot.getLocation())) {
                continue;
            }

            int score = 0;
            MapLocation robotLoc = robot.getLocation();

            // Highest priority: adjacent to our ally king
            if (visibleAllyKing != null && isLocationAdjacentToRatKing(robotLoc, visibleAllyKing)) {
                score += 1000;
            }

            // Prioritize lower HP (easier to finish off after throwing)
            // score += (200 - robot.getHealth());
            score += robot.getHealth(); // Try throwing higher HP to get out of way

            if (score > bestScore) {
                bestScore = score;
                bestRatnappable = robot;
            }
        }

        return bestRatnappable;
    }

    /**
     * Try to ratnap an adjacent enemy baby rat.
     * Returns true if we successfully ratnapped.
     */
    static boolean tryRatnap() throws GameActionException {
        RobotInfo ratnappable = findRatnappableRat();
        if (ratnappable != null) {
            MapLocation ratLoc = ratnappable.getLocation();
            if (rc.canCarryRat(ratLoc)) {
                rc.carryRat(ratLoc);
                return true;
            }
        }
        return false;
    }

    /**
     * Find the best direction to throw a carried rat.
     * Priority: toward nearest enemy, then toward nearest wall.
     * Returns null if no good throw direction found.
     */
    static Direction findBestThrowDirection() throws GameActionException {
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        // Find nearest enemy for targeting
        RobotInfo nearestEnemy = null;
        int nearestEnemyDist = Integer.MAX_VALUE;

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, enemyTeam);
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.CAT)
                continue; // Skip cats
            int dist = myLoc.distanceSquaredTo(robot.getLocation());
            if (dist < nearestEnemyDist) {
                nearestEnemyDist = dist;
                nearestEnemy = robot;
            }
        }

        // Check each direction for throwing
        for (Direction dir : DIRS) {
            MapLocation throwLoc = myLoc.add(dir);

            // Must have a gap (empty passable tile) in front to throw
            if (!rc.onTheMap(throwLoc))
                continue;
            if (map[throwLoc.x][throwLoc.y] == Tile.WALL)
                continue; // Wall

            // Check if tile is passable (not blocked by robot or wall)
            try {
                if (!rc.sensePassability(throwLoc))
                    continue;
                if (rc.senseRobotAtLocation(throwLoc) != null)
                    continue; // Blocked by robot
            } catch (GameActionException e) {
                continue; // Can't sense, skip
            }

            int score = 0;

            // Score based on enemy proximity
            if (nearestEnemy != null) {
                MapLocation enemyLoc = nearestEnemy.getLocation();
                Direction toEnemy = myLoc.directionTo(enemyLoc);

                // Higher score if throwing toward enemy
                if (dir == toEnemy) {
                    score += 100;
                } else if (dir == toEnemy.rotateLeft() || dir == toEnemy.rotateRight()) {
                    score += 50;
                }
            }

            // Check for walls in throw path - rat will take damage hitting walls
            // Look for walls 2-4 tiles away in throw direction
            for (int dist = 2; dist <= 4; dist++) {
                MapLocation checkLoc = myLoc.translate(dir.getDeltaX() * dist, dir.getDeltaY() * dist);
                if (!rc.onTheMap(checkLoc)) {
                    score += 30; // Off map = will hit edge
                    break;
                }
                if (map[checkLoc.x][checkLoc.y] == Tile.WALL) {
                    score += 30; // Will hit wall
                    break;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        // Only throw if we have a reasonable target (enemy or wall)
        if (bestScore > 0) {
            return bestDir;
        }

        // Fallback: just find any valid direction to throw
        for (Direction dir : DIRS) {
            MapLocation throwLoc = myLoc.add(dir);
            if (!rc.onTheMap(throwLoc))
                continue;
            try {
                if (rc.sensePassability(throwLoc) && rc.senseRobotAtLocation(throwLoc) == null) {
                    return dir;
                }
            } catch (GameActionException e) {
                continue;
            }
        }

        return null;
    }

    /**
     * Try to throw a carried rat toward the best target.
     * Returns true if we successfully threw.
     */
    static boolean tryThrowRat() throws GameActionException {
        if (rc.getCarrying() == null)
            return false;
        if (!rc.canThrowRat())
            return false;

        Direction throwDir = findBestThrowDirection();
        if (throwDir == null)
            return false;

        // Turn to face throw direction
        if (rc.getDirection() != throwDir) {
            if (rc.canTurn()) {
                rc.turn(throwDir);
            } else {
                return false; // Can't turn to throw
            }
        }

        if (rc.canThrowRat()) {
            rc.throwRat();
            return true;
        }

        return false;
    }

    // ------------------------------
    // Trap Placement Logic
    // ------------------------------

    /**
     * Count enemy rats in our vision cone (excluding cats).
     */
    static int countEnemyRatsInVision() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, enemyTeam);
        int count = 0;
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if there is an adjacent enemy rat.
     */
    static boolean hasAdjacentEnemyRat() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(2, enemyTeam);
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT) {
                if (myLoc.isAdjacentTo(robot.getLocation())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Find the closest enemy rat king in vision.
     * Returns null if no enemy rat king visible.
     */
    static RobotInfo findVisibleEnemyRatKing() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, enemyTeam);
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.RAT_KING) {
                return robot;
            }
        }
        return null;
    }

    /**
     * Get distance to enemy rat king considering its 3x3 size.
     * Returns the minimum distance to any part of the rat king.
     */
    static int getDistanceToRatKing(MapLocation kingCenter) {
        // Rat king is 3x3 centered at kingCenter
        // Find the closest tile of the rat king
        int minDist = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation kingTile = kingCenter.translate(dx, dy);
                int dist = myLoc.distanceSquaredTo(kingTile);
                if (dist < minDist) {
                    minDist = dist;
                }
            }
        }
        return minDist;
    }

    /**
     * Check if we're only 1 tile away from the rat king's 3x3 body.
     * This means we're adjacent to the 3x3 but not inside it.
     */
    static boolean isOneTileFromRatKing(MapLocation kingCenter) {
        int distToEdge = getDistanceToRatKing(kingCenter);
        // Distance squared of 1 = adjacent, 2 = diagonal adjacent
        return distToEdge <= 2 && distToEdge >= 1;
    }

    /**
     * Check if we're within 2 tiles of the rat king's 3x3 body.
     */
    static boolean isWithinTwoTilesOfRatKing(MapLocation kingCenter) {
        int distToEdge = getDistanceToRatKing(kingCenter);
        // Distance squared <= 4 means within 2 tiles
        return distToEdge <= 4;
    }

    /**
     * Get the average direction toward a group of enemy rats.
     */
    static Direction getDirectionTowardEnemyRats() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, enemyTeam);
        int totalDx = 0;
        int totalDy = 0;
        int count = 0;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING) {
                MapLocation loc = robot.getLocation();
                totalDx += loc.x - myLoc.x;
                totalDy += loc.y - myLoc.y;
                count++;
            }
        }

        if (count == 0)
            return null;

        // Get direction toward average position
        MapLocation avgPos = new MapLocation(myLoc.x + totalDx / count, myLoc.y + totalDy / count);
        return myLoc.directionTo(avgPos);
    }

    /**
     * Try to place a rat trap in a specific direction.
     * Returns true if trap was placed.
     */
    static boolean tryPlaceTrapInDirection(Direction dir) throws GameActionException {
        if (rc.getAllCheese() <= CHEESE_CRITICAL_THRESHOLD)
            return false;
        if (dir == null || dir == Direction.CENTER)
            return false;

        MapLocation trapLoc = myLoc.add(dir);
        if (rc.canPlaceRatTrap(trapLoc)) {
            rc.placeRatTrap(trapLoc);
            return true;
        }

        // Try adjacent directions if direct fails
        Direction left = dir.rotateLeft();
        MapLocation leftLoc = myLoc.add(left);
        if (rc.canPlaceRatTrap(leftLoc)) {
            rc.placeRatTrap(leftLoc);
            return true;
        }

        Direction right = dir.rotateRight();
        MapLocation rightLoc = myLoc.add(right);
        if (rc.canPlaceRatTrap(rightLoc)) {
            rc.placeRatTrap(rightLoc);
            return true;
        }

        return false;
    }

    /**
     * Try to place a trap in a given direction, with fallback to adjacent
     * directions.
     * Returns true if a trap was placed.
     * ONLY TO BE USED BY KING UNIT since he is 3x3
     */
    static boolean kingTryPlaceTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER)
            return false;

        MapLocation s;

        switch (dir) {
            case NORTH: {
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case SOUTH: {
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case EAST: {
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case WEST: {
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case NORTHEAST: {
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case SOUTHEAST: {
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case NORTHWEST: {
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            case SOUTHWEST: {
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceRatTrap(s)) {
                    rc.placeRatTrap(s);
                    return true;
                }
                return false;
            }

            default:
                return false;
        }
    }

    /**
     * Try to place a cat trap in a given direction, with fallback to adjacent
     * directions.
     * Returns true if a trap was placed.
     * ONLY TO BE USED BY KING UNIT since he is 3x3
     */
    static boolean kingTryPlaceCatTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER)
            return false;

        MapLocation s;

        switch (dir) {
            case NORTH: {
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case SOUTH: {
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case EAST: {
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case WEST: {
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case NORTHEAST: {
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case SOUTHEAST: {
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case NORTHWEST: {
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            case SOUTHWEST: {
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceCatTrap(s)) {
                    rc.placeCatTrap(s);
                    return true;
                }
                return false;
            }

            default:
                return false;
        }
    }

    /**
     * Handle trap placement behavior.
     * Should be called during turn to place traps strategically.
     * Returns the number of traps placed.
     */
    static int handleTrapPlacement() throws GameActionException {
        int trapsPlaced = 0;

        // Check for nearby enemy rat king first (highest priority)
        RobotInfo enemyKing = findVisibleEnemyRatKing();

        if (enemyKing != null) {
            MapLocation kingCenter = enemyKing.getLocation();
            boolean withinTwoTiles = isWithinTwoTilesOfRatKing(kingCenter);
            boolean oneTileAway = isOneTileFromRatKing(kingCenter);
            int cheese = rc.getAllCheese();

            // If within 2 tiles of rat king, always try to place trap toward it
            if (withinTwoTiles) {
                Direction toKing = myLoc.directionTo(kingCenter);

                if (tryPlaceTrapInDirection(toKing)) {
                    trapsPlaced++;
                }

                // If only 1 tile away AND have enough cheese (>500), place second trap
                if (oneTileAway && cheese > 500 && trapsPlaced < 2) {
                    // Try to place a second trap in slightly different direction
                    Direction left = toKing.rotateLeft();
                    Direction right = toKing.rotateRight();

                    if (tryPlaceTrapInDirection(left)) {
                        trapsPlaced++;
                    } else if (tryPlaceTrapInDirection(right)) {
                        trapsPlaced++;
                    }
                }

                return trapsPlaced;
            }
        }

        // If no rat king nearby, check for groups of enemy rats
        int enemyRatCount = countEnemyRatsInVision();
        boolean hasAdjacent = hasAdjacentEnemyRat();

        // If near our own rat king and see ANY enemy rat, place trap toward them
        if (enemyRatCount >= 1 && isNearAllyRatKing()) {
            Direction towardEnemies = getDirectionTowardEnemyRats();
            if (towardEnemies != null) {
                if (tryPlaceTrapInDirection(towardEnemies)) {
                    trapsPlaced++;
                }
            }
            return trapsPlaced;
        }

        // If 3+ enemy rats visible and none adjacent, place trap toward them
        if (enemyRatCount >= 3 && !hasAdjacent) {
            Direction towardEnemies = getDirectionTowardEnemyRats();
            if (towardEnemies != null) {
                if (tryPlaceTrapInDirection(towardEnemies)) {
                    trapsPlaced++;
                }
            }
        }

        return trapsPlaced;
    }

    /**
     * Check if this baby rat is within 10 tiles of any allied rat king.
     * Uses squared distance of 100 (10^2).
     */
    static boolean isNearAllyRatKing() throws GameActionException {
        for (int i = 0; i < 5; i++) {
            int encodedPos = rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + i);
            if (encodedPos == 0)
                continue; // No king in this slot

            MapLocation kingLoc = decodeLocation(encodedPos);
            int dist = myLoc.distanceSquaredTo(kingLoc);
            if (dist <= 100) { // Within 10 tiles
                return true;
            }
        }
        return false;
    }

    // ------------------------------
    // Squeak Communication
    // ------------------------------

    /**
     * Encode a squeak message with a type and location.
     * Format: [type (8 bits)][encoded location (24 bits)]
     */
    static int encodeSqueak(int messageType, MapLocation loc) {
        return (messageType << 24) | encodeLocation(loc);
    }

    /**
     * Get the message type from an encoded squeak.
     */
    static int getSqueakType(int squeak) {
        return (squeak >> 24) & 0xFF;
    }

    /**
     * Get the location from an encoded squeak.
     */
    static MapLocation getSqueakLocation(int squeak) {
        return decodeLocation(squeak & 0xFFFFFF);
    }

    /**
     * Squeak the enemy rat king location to nearby allies.
     * DO NOT squeak while evading - cats can hear squeaks!
     * Squeaks every turn when attacking king to ensure info propagates.
     */
    static void squeakEnemyKingLocation() throws GameActionException {
        if (currentState != State.EVADE && enemyRatKingLoc != null) {
            rc.squeak(encodeSqueak(SQUEAK_TYPE_ENEMY_KING, enemyRatKingLoc));
        }
    }

    /**
     * Listen for squeaks and process based on message type.
     * If we're further from the enemy king than the sender, re-squeak to propagate
     * info.
     */
    static void listenForSqueaks() throws GameActionException {
        int currentRound = rc.getRoundNum();
        boolean shouldResqueak = false;

        // Check squeaks from last 5 rounds
        for (int r = Math.max(1, currentRound - 5); r <= currentRound; r++) {
            Message[] messages = rc.readSqueaks(r);
            for (Message msg : messages) {
                int squeakData = msg.getBytes();
                int messageType = getSqueakType(squeakData);
                MapLocation decodedLoc = getSqueakLocation(squeakData);
                MapLocation senderLoc = msg.getSource();

                if (messageType == SQUEAK_TYPE_ENEMY_KING) {
                    // Handle enemy king location squeak
                    if (enemyRatKingLoc == null || r > enemyRatKingLastSeen) {
                        enemyRatKingLoc = decodedLoc;
                        enemyRatKingLastSeen = r;

                        // Check if we should propagate this squeak
                        if (senderLoc != null) {
                            int senderDistToKing = senderLoc.distanceSquaredTo(decodedLoc);
                            int myDistToKing = myLoc.distanceSquaredTo(decodedLoc);

                            if (myDistToKing > senderDistToKing) {
                                shouldResqueak = true;
                            }
                        }
                    }
                }
            }
        }

        // Re-squeak to propagate enemy king location to rats further away
        if (shouldResqueak && enemyRatKingLoc != null) {
            squeakEnemyKingLocation();
        }
    }

    static void kingListenForSqueaks() throws GameActionException {
        int currentRound = rc.getRoundNum();

        // Check squeaks from last 5 rounds
        for (int r = Math.max(1, currentRound - 5); r <= currentRound; r++) {
            Message[] messages = rc.readSqueaks(r);
            for (Message msg : messages) {
                int squeakData = msg.getBytes();
                int messageType = getSqueakType(squeakData);

                if (messageType == SQUEAK_TYPE_CHEESE_MINE) {
                    MapLocation decodedLoc = getSqueakLocation(squeakData);
                    storeMineLocation(decodedLoc);
                } else if (messageType == SQUEAK_TYPE_SYMMETRY) {
                    // Only process if we don't have symmetry set yet
                    int sharedSymmetry = rc.readSharedArray(SHARED_SYMMETRY_INDEX);
                    if (sharedSymmetry == 0) {
                        int symmetryOrdinal = getSqueakSymmetryOrdinal(squeakData);
                        if (symmetryOrdinal >= 1 && symmetryOrdinal <= 3) {
                            rc.writeSharedArray(SHARED_SYMMETRY_INDEX, symmetryOrdinal);
                            determinedSymmetry = Symmetry.values()[symmetryOrdinal];
                            condenseSymmetricalMines();
                        }
                    }
                }
            }
        }
    }

    /**
     * Store a mine location in the shared array (only called by king).
     * Avoids duplicates and symmetrical duplicates (if symmetry is known).
     * Also updates the mine count at SHARED_MINE_COUNT_INDEX.
     */
    static void storeMineLocation(MapLocation mineLoc) throws GameActionException {
        int encodedMine = encodeLocation(mineLoc);
        int currentMineCount = rc.readSharedArray(SHARED_MINE_COUNT_INDEX);

        // Calculate symmetrical encoded position if symmetry is known
        int symmetricalEncoded = -1;
        if (determinedSymmetry != Symmetry.UNKNOWN) {
            MapLocation symLoc = getSymmetricLocation(mineLoc, determinedSymmetry);
            symmetricalEncoded = encodeLocation(symLoc);
        }

        // Check existing mines for duplicates
        for (int i = 0; i < currentMineCount; i++) {
            int storedValue = rc.readSharedArray(SHARED_MINES_START_INDEX + i);

            if (storedValue == encodedMine) {
                // Mine already stored - don't add duplicate
                return;
            }

            if (symmetricalEncoded != -1 && storedValue == symmetricalEncoded) {
                // Symmetrical mine already stored - don't add duplicate
                return;
            }
        }

        // Check if we have room for another mine
        int nextSlot = SHARED_MINES_START_INDEX + currentMineCount;
        if (nextSlot >= GameConstants.SHARED_ARRAY_SIZE) {
            // Array is full - ignore this mine
            return;
        }

        // Store the new mine and update count
        rc.writeSharedArray(nextSlot, encodedMine);
        rc.writeSharedArray(SHARED_MINE_COUNT_INDEX, currentMineCount + 1);
    }

    /**
     * Condense the mine array by removing symmetrical duplicates.
     * Called when symmetry is first determined (by king only).
     * Ensures mines are continuous with no gaps, followed by 0s.
     * Also updates the mine count at SHARED_MINE_COUNT_INDEX.
     */
    static void condenseSymmetricalMines() throws GameActionException {
        if (determinedSymmetry == Symmetry.UNKNOWN)
            return;
        if (rc.getType() != UnitType.RAT_KING)
            return;

        int currentMineCount = rc.readSharedArray(SHARED_MINE_COUNT_INDEX);

        // Read all current mines into a temporary array
        int[] uniqueMines = new int[GameConstants.SHARED_ARRAY_SIZE - SHARED_MINES_START_INDEX];
        int uniqueCount = 0;

        for (int i = 0; i < currentMineCount; i++) {
            int storedValue = rc.readSharedArray(SHARED_MINES_START_INDEX + i);

            if (storedValue == 0) {
                // Reached end of mines
                break;
            }

            // Check if this mine or its symmetrical counterpart is already in uniqueMines
            MapLocation mineLoc = decodeLocation(storedValue);
            MapLocation symLoc = getSymmetricLocation(mineLoc, determinedSymmetry);
            int symmetricalEncoded = encodeLocation(symLoc);

            boolean isDuplicate = false;
            for (int j = 0; j < uniqueCount; j++) {
                if (uniqueMines[j] == storedValue || uniqueMines[j] == symmetricalEncoded) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                uniqueMines[uniqueCount] = storedValue;
                uniqueCount++;
            }
        }

        // Write back the condensed unique mines
        for (int i = 0; i < uniqueCount; i++) {
            rc.writeSharedArray(SHARED_MINES_START_INDEX + i, uniqueMines[i]);
        }

        // Clear remaining slots with 0s
        for (int i = uniqueCount; i < currentMineCount; i++) {
            rc.writeSharedArray(SHARED_MINES_START_INDEX + i, 0);
        }

        // Update mine count
        rc.writeSharedArray(SHARED_MINE_COUNT_INDEX, uniqueCount);
    }

    // ------------------------------
    // Cat Detection and Evasion
    // ------------------------------

    /**
     * Get the closest tile of a cat's 2x2 body to the rat's current position.
     * Cat center is at smallest x,y and occupies (x,y), (x+1,y), (x,y+1),
     * (x+1,y+1).
     */
    static MapLocation getClosestCatTile(MapLocation catCenter) {
        MapLocation closest = catCenter;
        int closestDist = myLoc.distanceSquaredTo(catCenter);

        // Check all 4 tiles of the cat's 2x2 body
        MapLocation[] catTiles = {
                catCenter,
                catCenter.translate(1, 0),
                catCenter.translate(0, 1),
                catCenter.translate(1, 1)
        };

        for (MapLocation tile : catTiles) {
            int dist = myLoc.distanceSquaredTo(tile);
            if (dist < closestDist) {
                closestDist = dist;
                closest = tile;
            }
        }

        return closest;
    }

    /**
     * Check if we can see any cats nearby.
     * Returns the closest cat if found, null otherwise.
     * Note: Uses closest tile of cat's 2x2 body for distance calculation.
     */
    static RobotInfo findNearbyCat() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, Team.NEUTRAL);
        RobotInfo closestCat = null;
        int closestDist = Integer.MAX_VALUE;

        for (RobotInfo robot : nearbyRobots) {

            System.out.println(robot);
            // Get distance to closest tile of cat's 2x2 body
            MapLocation closestTile = getClosestCatTile(robot.getLocation());
            int dist = myLoc.distanceSquaredTo(closestTile);
            if (dist < closestDist) {
                closestDist = dist;
                closestCat = robot;
            }
        }
        return closestCat;
    }

    /**
     * Check for cat presence - either by direct sight or by detecting a scratch
     * from behind.
     * Updates catLastSeenLoc and catLastSeenRound if cat detected.
     * Returns true if cat detected.
     */
    static boolean detectCat() throws GameActionException {
        int currentRound = rc.getRoundNum();
        int currentHealth = rc.getHealth();
        boolean catDetected = false;

        // Method 1: Direct sight - we can see a cat
        RobotInfo cat = findNearbyCat();
        if (cat != null) {
            // Store the closest tile of the cat's 2x2 body for accurate flee direction
            catLastSeenLoc = getClosestCatTile(cat.getLocation());
            catLastSeenRound = currentRound;
            catDetected = true;
        }

        // Method 2: Indirect detection - sudden 50 HP drop (cat scratch from behind)
        // Only check if we didn't already see the cat and health was initialized
        if (!catDetected && lastHealth > 0) {
            int healthLost = lastHealth - currentHealth;
            if (healthLost >= GameConstants.CAT_SCRATCH_DAMAGE) {
                // We got scratched from behind! Cat is likely behind us (opposite our facing
                // direction)
                Direction facing = rc.getDirection();
                Direction behind = facing.opposite();

                // Estimate cat position as a few tiles behind us
                // Cat scratch range is within their vision cone, so assume close
                catLastSeenLoc = myLoc.add(behind).add(behind);
                catLastSeenRound = currentRound;
                catDetected = true;
            }
        }

        // Update last known health for next turn
        lastHealth = currentHealth;

        return catDetected;
    }

    /**
     * Check if we should exit EVADE state.
     * Exit if enough turns have passed without cat contact.
     */
    static boolean shouldExitEvade() {
        return currentState == State.EVADE && rc.getRoundNum() - catLastSeenRound >= EVADE_TIMEOUT_TURNS;
    }

    /**
     * Try to place a cat trap in a specific direction.
     * Returns true if trap was placed.
     */
    static boolean tryPlaceCatTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER)
            return false;

        MapLocation trapLoc = myLoc.add(dir);
        if (rc.canPlaceCatTrap(trapLoc)) {
            rc.placeCatTrap(trapLoc);
            return true;
        }

        // Try adjacent directions if direct fails
        Direction left = dir.rotateLeft();
        MapLocation leftLoc = myLoc.add(left);
        if (rc.canPlaceCatTrap(leftLoc)) {
            rc.placeCatTrap(leftLoc);
            return true;
        }

        Direction right = dir.rotateRight();
        MapLocation rightLoc = myLoc.add(right);
        if (rc.canPlaceCatTrap(rightLoc)) {
            rc.placeCatTrap(rightLoc);
            return true;
        }

        return false;
    }

    /**
     * Evade behavior - flee from cat.
     */
    static void doEvade() throws GameActionException {
        if (catLastSeenLoc == null) {
            currentState = State.EXPLORE;
            return;
        }

        int currentRound = rc.getRoundNum();
        turnsSinceLastCheck++;

        // Check for visible cat to get accurate position
        RobotInfo visibleCat = findNearbyCat();
        if (visibleCat != null) {
            catLastSeenLoc = getClosestCatTile(visibleCat.getLocation());
            catLastSeenRound = currentRound;

            // Calculate distance to closest cat tile
            int distToCat = myLoc.distanceSquaredTo(catLastSeenLoc);

            // Late game (turn > 1800): Attack adjacent cats before evading
            if (currentRound > 1800 && distToCat <= 2) {
                // Cat is adjacent, attack it!
                if (rc.canAttack(catLastSeenLoc)) {
                    rc.attack(catLastSeenLoc);
                }
            }

            // Cooperation mode (turn > 500): Place cat traps toward the cat
            if (currentRound > 500 && rc.isCooperation()) {
                Direction toCat = myLoc.directionTo(catLastSeenLoc);
                tryPlaceCatTrapInDirection(toCat);
            }
        }

        // Periodically turn around to check if cat is still there (every 3-4 turns)
        if (turnsSinceLastCheck >= 4) {
            Direction toCat = myLoc.directionTo(catLastSeenLoc);
            if (rc.canTurn() && rc.getDirection() != toCat) {
                rc.turn(toCat);
                turnsSinceLastCheck = 0;

                // After turning, check if we can see the cat
                RobotInfo cat = findNearbyCat();
                if (cat != null) {
                    // Store closest tile for accurate flee direction
                    catLastSeenLoc = getClosestCatTile(cat.getLocation());
                    catLastSeenRound = currentRound;
                } else {
                    // Cat not visible - maybe we escaped?
                    // The timeout will handle exiting EVADE
                }
                return; // Used our turn for checking
            }
        }

        // Calculate flee direction (away from cat)
        Direction awayFromCat = catLastSeenLoc.directionTo(myLoc);
        if (awayFromCat == Direction.CENTER) {
            // We're on top of cat's last position (unlikely), pick a direction
            awayFromCat = DIRS[rng.nextInt(8)];
        }

        // Try to move away from cat
        MapLocation fleeTarget = myLoc.add(awayFromCat).add(awayFromCat).add(awayFromCat);

        // Clamp to map bounds
        fleeTarget = new MapLocation(
                Math.max(0, Math.min(mapWidth - 1, fleeTarget.x)),
                Math.max(0, Math.min(mapHeight - 1, fleeTarget.y)));

        BugNav.goTo(fleeTarget);

        // Check if we should exit evade
        if (shouldExitEvade()) {
            currentState = State.EXPLORE;
            catLastSeenLoc = null;
            evadeStartRound = -1;
            turnsSinceLastCheck = 0;
        }
    }

    // ------------------------------
    // Cheese Collection Logic
    // ------------------------------

    /**
     * Find the nearest location with cheese that we can pick up.
     * Returns null if no cheese found.
     */
    static MapLocation findNearbyCheeseLocation() throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation bestCheese = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : nearby) {
            if (tile.getCheeseAmount() > 0) {
                MapLocation loc = tile.getMapLocation();
                int dist = myLoc.distanceSquaredTo(loc);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestCheese = loc;
                }
            }
        }
        return bestCheese;
    }

    /**
     * Check if there are any ally baby rats nearby.
     */
    static boolean hasAllyBabyRatsNearby() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, myTeam);
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determine if we should enter COLLECT mode.
     * Returns true if we should start collecting cheese.
     */
    static boolean shouldEnterCollectMode() throws GameActionException {
        int teamCheese = rc.getGlobalCheese();

        // If cheese is above normal threshold, reset and don't collect
        if (teamCheese >= CHEESE_EMERGENCY_THRESHOLD) {
            wasCheeseAboveThreshold = true;
            return false;
        }

        // Check if there's cheese nearby
        MapLocation cheeseLoc = findNearbyCheeseLocation();
        if (cheeseLoc == null)
            return false;

        // If no ally baby rats nearby, always collect
        if (!hasAllyBabyRatsNearby()) {
            return true;
        }

        // Roll for collector role (40% chance - keep most rats attacking)
        if (wasCheeseAboveThreshold && !hasDecidedCollectorRole) {
            isCollectorRole = rng.nextInt(10) < 4; // 40% chance to be a collector
            hasDecidedCollectorRole = true;
        }
        wasCheeseAboveThreshold = false;

        return isCollectorRole;
    }

    /**
     * Find the nearest rat king location by scanning all 5 possible king slots.
     * Returns null if no kings found.
     */
    static MapLocation findNearestRatKing() throws GameActionException {
        MapLocation nearestKing = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int i = 0; i < 5; i++) {
            int encodedPos = rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + i);
            if (encodedPos == 0)
                continue;

            MapLocation kingLoc = decodeLocation(encodedPos);
            int dist = myLoc.distanceSquaredTo(kingLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestKing = kingLoc;
            }
        }

        return nearestKing;
    }

    /**
     * Collect cheese behavior - navigate to cheese or known mines.
     * Cheese pickup is handled automatically in senseNearby().
     */
    static void doCollect() throws GameActionException {
        // Check if we have cheese to return
        if (!shouldCollectCheese()) {
            currentState = State.RETURN;
            currentMineTarget = null;
            doReturn();
            return;
        }
        // Find cheese to navigate toward
        MapLocation cheeseLoc = findNearbyCheeseLocation();

        if (cheeseLoc != null) {
            // Navigate toward the cheese (pickup handled in senseNearby)
            BugNav.goTo(cheeseLoc);
            return;
        } else if (rc.getRawCheese() > 0) {
            currentState = State.RETURN;
            currentMineTarget = null;
            doReturn();
            return;
        }

        // No cheese visible - check if we know any mines
        if (mineLocations.isEmpty()) {
            // No known mines, go explore to find some
            currentState = State.EXPLORE;
            return;
        }

        // Navigate to known mines
        // If we're at our current mine target and no cheese, mark it as checked
        if (currentMineTarget != null && myLoc.distanceSquaredTo(currentMineTarget) <= 2) {
            // Arrived at mine but no cheese - mark as checked and turn around
            checkedMines.add(encodeLocation(currentMineTarget));
            currentMineTarget = null;
            turnAround();
        }

        // Find the nearest unchecked mine
        if (currentMineTarget == null) {
            currentMineTarget = findNearestUncheckedMine();
        }

        if (currentMineTarget != null) {
            // Navigate to the mine
            BugNav.goTo(currentMineTarget);
            return;
        }

        // No more unchecked mines - reset and go back to exploring
        checkedMines.clear();
        currentMineTarget = null;
        currentState = State.EXPLORE;
    }

    /**
     * Turn around to look in the opposite direction.
     */
    static void turnAround() throws GameActionException {
        Direction opposite = rc.getDirection().opposite();
        if (rc.canTurn()) {
            rc.turn(opposite);
        }
    }

    /**
     * Find the nearest mine from mineLocations that hasn't been checked yet.
     * Returns null if no unchecked mines exist.
     */
    static MapLocation findNearestUncheckedMine() {
        // Update assigned mine index if we have more mines now
        if (!mineLocations.isEmpty() && assignedMineIndex == -1) {
            assignedMineIndex = rc.getID() % mineLocations.size();
        }

        // First, try our assigned mine (to spread rats across mines)
        if (assignedMineIndex >= 0 && assignedMineIndex < mineLocations.size()) {
            int i = 0;
            for (int encodedMine : mineLocations) {
                if (i == assignedMineIndex && !checkedMines.contains(encodedMine)) {
                    return decodeLocation(encodedMine);
                }
                i++;
            }
        }

        // Fallback: find nearest unchecked mine
        MapLocation nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int encodedMine : mineLocations) {
            if (checkedMines.contains(encodedMine)) {
                continue; // Skip already checked mines
            }

            MapLocation mineLoc = decodeLocation(encodedMine);
            int dist = myLoc.distanceSquaredTo(mineLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = mineLoc;
            }
        }

        return nearest;
    }

    /**
     * Return to rat king and deliver cheese.
     */
    static void doReturn() throws GameActionException {
        int myCheese = rc.getRawCheese();

        if (myCheese <= 0) {
            currentState = State.FIND_KING;
            return;
        }

        // Try to transfer cheese to king if adjacent
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation kingTile = ratKingLoc.translate(dx, dy);
                if (rc.canTransferCheese(kingTile, myCheese)) {
                    rc.transferCheese(kingTile, myCheese);
                    currentState = State.FIND_KING;
                    return;
                }
            }
        }

        BugNav.goTo(ratKingLoc);
    }

    // ------------------------------
    // State Machine Logic
    // ------------------------------

    /**
     * Check if we're in a recovery situation (failed rush, low resources).
     * Returns true if we should focus on rebuilding rather than attacking.
     */
    static boolean isRecoveryPhase() throws GameActionException {
        int round = rc.getRoundNum();
        int cheese = rc.getGlobalCheese();

        // After initial rush phase (round 100+), if cheese is low, enter recovery
        if (round > 100 && cheese < 300) {
            return true;
        }

        // If we're in mid-game with very low cheese, recover
        if (round > 200 && cheese < 500) {
            return true;
        }

        return false;
    }

    /**
     * Check if our king is nearby and under threat, requiring defense.
     * Returns the enemy threatening our king, or null if no threat.
     */
    static RobotInfo checkKingDefense() throws GameActionException {
        // Find our nearest king
        MapLocation ourKing = findNearestRatKing();
        if (ourKing == null)
            return null;

        // Only defend if we're reasonably close to our king
        int distToOurKing = myLoc.distanceSquaredTo(ourKing);
        if (distToOurKing > 100)
            return null; // Too far to defend effectively

        // Check for enemies near our king
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);
        RobotInfo closestThreat = null;
        int closestThreatDist = Integer.MAX_VALUE;

        for (RobotInfo enemy : nearbyEnemies) {
            if (enemy.getType() == UnitType.CAT)
                continue; // Cat handled separately

            // Check if this enemy is close to our king
            int enemyDistToKing = enemy.getLocation().distanceSquaredTo(ourKing);
            if (enemyDistToKing <= 36) { // Enemy within 6 tiles of our king
                int distToEnemy = myLoc.distanceSquaredTo(enemy.getLocation());
                if (distToEnemy < closestThreatDist) {
                    closestThreatDist = distToEnemy;
                    closestThreat = enemy;
                }
            }
        }

        return closestThreat;
    }

    static void updateState() throws GameActionException {
        int currentRound = rc.getRoundNum();

        // First priority: Check for cat presence
        boolean catDetected = detectCat();

        // After turn 1800, only evade cats if we took damage (cat scratched us)
        boolean shouldEvadeCat = catDetected;
        if (currentRound > 1800 && catDetected) {
            // Only evade if we took damage this turn (health dropped)
            int currentHealth = rc.getHealth();
            // lastHealth is updated in detectCat(), so check if we lost health
            // We check if health lost >= cat scratch damage (we got hit)
            shouldEvadeCat = (lastHealth > 0 && (lastHealth - currentHealth) >= GameConstants.CAT_SCRATCH_DAMAGE);
        }

        // If cat detected and we should evade, enter EVADE mode
        if (shouldEvadeCat) {
            if (currentState != State.EVADE) {
                evadeStartRound = currentRound;
                turnsSinceLastCheck = 0;
            }
            currentState = State.EVADE;
            return; // Don't process other state logic when evading
        }

        // If currently evading, check if we should continue or exit
        if (currentState == State.EVADE) {
            if (shouldExitEvade()) {
                currentState = State.FIND_KING;
                catLastSeenLoc = null;
                evadeStartRound = -1;
                turnsSinceLastCheck = 0;
            } else {
                return; // Stay in EVADE mode
            }
        }

        // Check if we should enter COLLECT mode (emergency cheese strategy)
        // Only check if not already collecting/returning and we have known mines
        if (currentState != State.COLLECT && currentState != State.RETURN) {
            if (!mineLocations.isEmpty() && shouldEnterCollectMode()) {
                currentState = State.COLLECT;
                checkedMines.clear();
                currentMineTarget = null;
                return;
            }
        }

        // If currently collecting or returning, stay in that mode
        // (the do functions will handle state transitions)
        if (currentState == State.COLLECT || currentState == State.RETURN) {
            return;
        }

        // Normal state logic (when not evading or collecting)
        RobotInfo target = findBestTarget();
        attackTarget = target;

        // Listen for squeaks about enemy king (but NOT while evading - handled above)
        listenForSqueaks();

        // Check cheese level for priority decisions
        int teamCheese = rc.getGlobalCheese();
        boolean cheeseCritical = teamCheese < CHEESE_CRITICAL_THRESHOLD;
        boolean inRecovery = isRecoveryPhase();

        // Priority 1: Defend our king if enemies are attacking it
        RobotInfo kingThreat = checkKingDefense();
        if (kingThreat != null) {
            attackTarget = kingThreat;
            currentState = State.ATTACK;
            return;
        }

        // Priority 2: Attack any nearby enemies
        if (target != null) {
            // Enemy detected nearby - ALWAYS attack (combat takes priority)
            currentState = State.ATTACK;
        } else if (enemyRatKingLoc != null && !inRecovery) {
            // We know where enemy king is - Rush to kill it! (but not during recovery)
            int distToKing = myLoc.distanceSquaredTo(enemyRatKingLoc);

            if (distToKing <= 25) {
                // Close enough, enter ATTACK mode
                currentState = State.ATTACK;
            } else {
                // Far away, RUSH toward king
                currentState = State.RUSH;
            }
        } else if (inRecovery && !mineLocations.isEmpty()) {
            // Recovery phase: scouts explore for mines, collectors gather cheese
            if (isScoutRole && !visitedAll) {
                currentState = State.EXPLORE;
            } else if (isCollectorRole) {
                currentState = State.COLLECT;
                checkedMines.clear();
                currentMineTarget = null;
            } else {
                // Non-scouts, non-collectors: patrol near our king for defense
                currentState = State.FIND_KING;
            }
        } else if (cheeseCritical && !mineLocations.isEmpty() && isCollectorRole) {
            // Only collect if cheese is TRULY critical AND we're designated collector
            currentState = State.COLLECT;
            checkedMines.clear();
            currentMineTarget = null;
        } else {
            // No intel - stay in current mode (FIND_KING or EXPLORE)
            // Scouts keep exploring until they've visited everything
            if (isScoutRole && !visitedAll) {
                currentState = State.EXPLORE;
            } else if (currentState != State.FIND_KING && currentState != State.EXPLORE) {
                currentState = State.FIND_KING;
            }
        }
    }

    // ------------------------------
    // Attack Logic
    // ------------------------------

    static int getMinCheeseForExtraDamage(int extraDamage) {
        if (extraDamage <= 0) {
            return 0;
        }
        // For X extra damage, minimum cheese is (X-1)^2 + 1
        // Examples: 1 damage -> 1 cheese, 2 damage -> 2 cheese, 3 damage -> 5 cheese
        return (extraDamage - 1) * (extraDamage - 1) + 1;
    }

    /**
     * Determine how much cheese to spend on an attack.
     * Conservative cheese usage to preserve resources.
     * Returns 2 for max damage, 0 for base attack.
     */
    static int getCheeseForAttack(boolean isAttackingKing, int enemyHealth) throws GameActionException {
        int cheese = rc.getAllCheese();
        int gap = Math.max(enemyHealth - GameConstants.RAT_BITE_DAMAGE, 0);
        int spend = getMinCheeseForExtraDamage(gap);

        // When attacking rat king, spend cheese if we have plenty
        if (isAttackingKing) {
            if (spend <= cheese - 2) {
                return spend;
            } else if (cheese > 200) {
                return 2;
            }
        }

        // Spend 2 cheese only if we have lots
        if (cheese > 500) {
            if (gap <= 4) {
                return spend;
            }
            return 2;
        } else if (cheese > 100 && gap <= 2) {
            return spend;
        }

        return 0;
    }

    /**
     * Try to attack a location, using cheese for extra damage if appropriate.
     * Returns true if attack was performed.
     */
    static boolean tryAttackWithCheese(MapLocation attackLoc, boolean isKing, int enemyHealth)
            throws GameActionException {
        int cheeseToUse = getCheeseForAttack(isKing, enemyHealth);

        if (cheeseToUse > 0 && rc.canAttack(attackLoc, cheeseToUse)) {
            rc.attack(attackLoc, cheeseToUse);
            return true;
        } else if (rc.canAttack(attackLoc)) {
            rc.attack(attackLoc);
            return true;
        }

        return false;
    }

    static void doAttack() throws GameActionException {
        if (attackTarget == null) {
            // No target visible, but we might be near known king location
            if (enemyRatKingLoc != null && myLoc.distanceSquaredTo(enemyRatKingLoc) <= 25) {
                BugNav.goTo(enemyRatKingLoc);
            } else {
                currentState = State.EXPLORE;
            }
            return;
        }

        MapLocation targetLoc = attackTarget.getLocation();
        boolean isKing = attackTarget.getType() == UnitType.RAT_KING;
        boolean isCat = attackTarget.getType() == UnitType.CAT;
        int myHealth = rc.getHealth();
        int targetHealth = attackTarget.getHealth();

        // If it's a rat king, squeak its location
        if (isKing) {
            squeakEnemyKingLocation();
        }

        // Determine attack location based on target type
        MapLocation attackLoc;
        if (isKing) {
            attackLoc = getBestAttackLocationForKing(targetLoc);
        } else if (isCat) {
            // Cat is 2x2, attack closest tile of its body
            attackLoc = getBestAttackLocationForCat(targetLoc);
        } else {
            attackLoc = targetLoc;
        }

        if (attackLoc == null)
            return;

        // KITING LOGIC: If we're low HP and target is high HP, attack then retreat
        // Always kite against cats regardless of HP
        boolean shouldKite = isCat || (!isKing && myHealth < targetHealth && myHealth <= 50);

        // Try to attack first (attack-move pattern)
        boolean attacked = tryAttackWithCheese(attackLoc, isKing, targetHealth);

        if (attacked && shouldKite && rc.isMovementReady()) {
            // After attacking, move away from the target
            Direction awayFromTarget = targetLoc.directionTo(myLoc);
            if (awayFromTarget != Direction.CENTER) {
                if (rc.canMove(awayFromTarget)) {
                    rc.move(awayFromTarget);
                    return;
                }
            }
        }

        if (attacked)
            return; // Successfully attacked, don't need to move closer

        // Need to get closer - turn toward target first
        Direction dirToTarget = myLoc.directionTo(attackLoc);
        if (dirToTarget != Direction.CENTER && dirToTarget != rc.getDirection()) {
            if (rc.canTurn()) {
                rc.turn(dirToTarget);
            }
        }

        // Move toward target and try to attack after
        if (rc.isMovementReady()) {
            if (!myLoc.isAdjacentTo(attackLoc)) {
                BugNav.goTo(attackLoc);
            }
            // After moving, try to attack
            tryAttackWithCheese(attackLoc, isKing, targetHealth);
        }
    }

    static void doAttackAsKing() throws GameActionException {
        if (attackTarget == null) {
            return;
        }

        MapLocation targetLoc = attackTarget.getLocation();
        boolean isKing = attackTarget.getType() == UnitType.RAT_KING;
        boolean isCat = attackTarget.getType() == UnitType.CAT;

        MapLocation attackLoc;
        if (isKing) {
            attackLoc = getBestAttackLocationForKing(targetLoc);
        } else if (isCat) {
            attackLoc = getBestAttackLocationForCat(targetLoc);
        } else {
            attackLoc = targetLoc;
        }

        if (attackLoc == null)
            return;

        tryAttackWithCheese(attackLoc, isKing, attackTarget.getHealth());
    }

    // ------------------------------
    // Rush Logic
    // ------------------------------
    static void doRush() throws GameActionException {
        if (enemyRatKingLoc == null) {
            currentState = State.FIND_KING;
            return;
        }

        // Move toward enemy rat king
        BugNav.goTo(enemyRatKingLoc);

        // Check if we can see any enemies while rushing
        RobotInfo target = findBestTarget();
        if (target != null) {
            attackTarget = target;
            currentState = State.ATTACK;
            doAttack();
        }
    }

    // ------------------------------
    // Find King Logic (Baby Rats - searching for enemy king)
    // ------------------------------
    static void doFindKing() throws GameActionException {
        // Check if all possible spawns have been checked - switch to EXPLORE mode
        boolean allSpawnsChecked = spawnChecked[0] && spawnChecked[1] && spawnChecked[2];
        if (allSpawnsChecked && determinedSymmetry == Symmetry.UNKNOWN) {
            // We've checked all spawns but still don't know symmetry
            // This shouldn't happen often, but switch to explore mode
            currentState = State.EXPLORE;
            destination = null;
            return;
        }

        // If symmetry is determined and we've checked that spawn, switch to EXPLORE
        if (determinedSymmetry != Symmetry.UNKNOWN) {
            int confirmedIndex = determinedSymmetry.ordinal() - 1;
            if (spawnChecked[confirmedIndex]) {
                currentState = State.EXPLORE;
                destination = null;
                return;
            }
        }

        MapLocation targetSpawn = getCurrentSpawnTarget();

        //updateSpawnChecking();

        if (targetSpawn != null) {
            destination = targetSpawn;
            BugNav.setTarget(destination);
        }

        // Move using BugNav toward persistent destination
        if (destination != null) {
            BugNav.goTo(destination);
        }
    }

    // ------------------------------
    // Explore Logic (Baby Rats - general exploration)
    // ------------------------------
    static void doExplore() throws GameActionException {
        // If we don't have a destination or reached it, choose a new one
        if (destination == null || myLoc.distanceSquaredTo(destination) <= 2) {
            destination = chooseExploreTarget();
            BugNav.setTarget(destination);
            // Reset stuck detection for new destination
            exploreSmallestDistSq = Integer.MAX_VALUE;
            exploreTurnsWithoutProgress = 0;
        }

        // Track distance to destination for stuck detection
        if (destination != null) {
            int currentDistSq = myLoc.distanceSquaredTo(destination);
            if (currentDistSq < exploreSmallestDistSq) {
                // Making progress - update smallest distance and reset counter
                exploreSmallestDistSq = currentDistSq;
                exploreTurnsWithoutProgress = 0;
            } else {
                // Not making progress
                exploreTurnsWithoutProgress++;
                if (exploreTurnsWithoutProgress >= EXPLORE_STUCK_THRESHOLD) {
                    // Stuck for too long, choose a new destination
                    destination = chooseExploreTarget();
                    BugNav.setTarget(destination);
                    exploreSmallestDistSq = Integer.MAX_VALUE;
                    exploreTurnsWithoutProgress = 0;
                }
            }
        }

        // Move using BugNav toward destination
        if (destination != null) {
            BugNav.goTo(destination);
        }
    }

    // ------------------------------
    // Bug Navigation - Simplified Bug2 Algorithm
    // ------------------------------
    static final class BugNav {
        private static MapLocation target = null;

        // Bug state
        private static boolean bugging = false;
        private static boolean bugClockwise = true;
        private static int bugStartDist = Integer.MAX_VALUE; // Distance when we started bugging
        private static Direction bugWallDir = null; // Direction of the wall we're following

        // Stuck detection
        private static MapLocation lastPosition = null;
        private static int stuckCounter = 0;

        static void setTarget(MapLocation newTarget) {
            if (newTarget == null)
                return;
            if (target == null || !target.equals(newTarget)) {
                target = newTarget;
                resetBugState();
            }
        }

        private static void resetBugState() {
            bugging = false;
            bugClockwise = true;
            bugStartDist = Integer.MAX_VALUE;
            bugWallDir = null;
            stuckCounter = 0;
        }

        static void goTo(MapLocation dest) throws GameActionException {
            if (dest == null)
                return;
            setTarget(dest);

            MapLocation here = rc.getLocation();

            // Track stuck state for digging
            if (lastPosition != null && here.equals(lastPosition)) {
                stuckCounter++;
            } else {
                stuckCounter = 0;
            }
            lastPosition = here;

            // If stuck, try digging toward destination
            if (stuckCounter >= 3) {
                if (tryDigToward(dest)) {
                    return;
                }
            }

            if (!rc.isMovementReady())
                return;

            // Already at destination
            if (here.equals(dest)) {
                resetBugState();
                return;
            }

            int distToDest = here.distanceSquaredTo(dest);
            Direction dirToDest = here.directionTo(dest);

            // Try direct movement first (greedy)
            if (!bugging) {
                if (tryMoveDir(dirToDest)) {
                    return; // Greedy worked
                }

                // Greedy failed - start bugging
                bugging = true;
                bugStartDist = distToDest;
                bugWallDir = dirToDest; // The wall is in the direction we wanted to go
                // Choose bug direction based on robot ID for variety
                bugClockwise = (rc.getID() % 2 == 0);
            }

            // We're in bug mode - follow the wall
            if (bugging) {
                // Check if we can exit bug mode:
                // We can move toward target AND we're closer than when we started bugging
                if (rc.canMove(dirToDest) && distToDest < bugStartDist) {
                    if (tryMoveDir(dirToDest)) {
                        resetBugState();
                        return;
                    }
                }

                // Follow the wall
                if (!followWall()) {
                    // Couldn't follow wall - try flipping direction
                    bugClockwise = !bugClockwise;
                    followWall();
                }
            }
        }

        /**
         * Follow the wall by rotating around it
         */
        private static boolean followWall() throws GameActionException {
            if (bugWallDir == null)
                return false;

            // Start from the direction pointing INTO the wall, then rotate to find open
            // space
            // We rotate away from the wall to find the first open direction
            Direction d = bugClockwise ? bugWallDir.rotateRight() : bugWallDir.rotateLeft();

            for (int i = 0; i < 8; i++) {
                if (tryMoveDir(d)) {
                    // We moved in direction d
                    // Update wall direction: the wall is now roughly opposite to where we came
                    // from,
                    // rotated toward the wall side
                    bugWallDir = bugClockwise ? d.rotateLeft().rotateLeft() : d.rotateRight().rotateRight();
                    return true;
                }
                // Rotate to try next direction
                d = bugClockwise ? d.rotateRight() : d.rotateLeft();
            }

            return false; // Completely stuck
        }

        /**
         * Turn to face a direction if not already facing it.
         * Returns true if we're now facing the direction.
         * Returns false if we couldn't turn (already used turn action).
         */
        private static boolean turnToFace(Direction d) throws GameActionException {
            if (d == null || d == Direction.CENTER || rc.getDirection() == d) {
                return true;
            }

            if (rc.canTurn()) {
                rc.turn(d);
                return true;
            }

            return false;
        }

        /**
         * After turning to face a new direction, sense again and try actions.
         * Also checks if we should avoid moving (adjacent to cat or many enemies).
         * Returns: 0 = proceed with move, 1 = placed trap instead, 2 = should evade
         */
        private static int handlePostTurnActions(Direction moveDir) throws GameActionException {
            // Update myLoc (should still be same, but for consistency)
            myLoc = rc.getLocation();

            // Sense again with new orientation
            senseNearby();

            // Try actions with the new view
            if (rc.isActionReady()) {
                tryRatnap();
                tryThrowRat();
                tryAttackNearbyEnemy();
            }

            // Check what's at the destination if we move
            MapLocation destLoc = myLoc.add(moveDir);

            // Check for cat adjacency at destination
            if (wouldBeAdjacentToCat(destLoc)) {
                // Trigger evade instead of moving
                return 2; // Signal to evade
            }

            // Check for 2+ enemy rats adjacent to destination
            int adjacentEnemyRats = countAdjacentEnemyRats(destLoc);
            if (adjacentEnemyRats >= 2) {
                // Place rat trap in front instead of moving
                if (tryPlaceTrapInDirection(moveDir)) {
                    return 1; // Placed trap, don't move
                }
            }

            return 0; // Proceed with move
        }

        /**
         * Check if a location would be adjacent to any tile of a cat (2x2 unit).
         * Cat's location is the tile with smallest x and y, occupying (x,y), (x+1,y),
         * (x,y+1), (x+1,y+1).
         */
        private static boolean wouldBeAdjacentToCat(MapLocation loc) throws GameActionException {
            RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);
            for (RobotInfo enemy : nearbyEnemies) {
                if (enemy.getType() == UnitType.CAT) {
                    MapLocation catCenter = enemy.getLocation();
                    // Cat occupies 4 tiles
                    MapLocation[] catTiles = {
                            catCenter,
                            catCenter.translate(1, 0),
                            catCenter.translate(0, 1),
                            catCenter.translate(1, 1)
                    };
                    for (MapLocation catTile : catTiles) {
                        if (loc.isAdjacentTo(catTile) || loc.equals(catTile)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Count enemy rats (baby rats and rat kings) that would be adjacent to a
         * location.
         */
        private static int countAdjacentEnemyRats(MapLocation loc) throws GameActionException {
            RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);
            int count = 0;
            for (RobotInfo enemy : nearbyEnemies) {
                UnitType type = enemy.getType();
                if (type == UnitType.BABY_RAT || type == UnitType.RAT_KING) {
                    MapLocation enemyLoc = enemy.getLocation();
                    if (loc.isAdjacentTo(enemyLoc) || loc.equals(enemyLoc)) {
                        count++;
                    }
                }
            }
            return count;
        }

        /**
         * Try to move in a direction, including adjacent directions if direct fails.
         * Will turn to face the movement direction first to avoid movement penalty.
         * If we move forward without needing to turn first, we rotate randomly after
         * moving and try actions again (ratnap, throw, attack).
         * If we need to turn first, we sense and try actions after turning, then
         * check if moving would be dangerous before proceeding.
         */

        private static boolean tryMoveDir(Direction d) throws GameActionException {
            if (d == null || d == Direction.CENTER)
                return false;

            // Try exact direction first
            if (!rc.canMove(d)) {
                // If blocked, check if it's dirt and dig through 50% of the time
                MapLocation destLoc = rc.getLocation().add(d);
                if (rc.onTheMap(destLoc) && map[destLoc.x][destLoc.y] == Tile.DIRT
                        && rc.getGlobalCheese() >= GameConstants.DIG_DIRT_CHEESE_COST) {
                    if (decideDig()) {
                        if (rc.canRemoveDirt(destLoc)) {
                            rc.removeDirt(destLoc);
                        }
                    }
                }
            }

            if (rc.canMove(d)) {
                boolean alreadyFacing = (rc.getDirection() == d);

                if (!alreadyFacing) {
                    // Need to turn first
                    if (!turnToFace(d)) {
                        return false; // Couldn't turn
                    }
                    // After turning, handle post-turn actions and safety checks
                    int result = handlePostTurnActions(d);
                    if (result == 1) {
                        // Placed trap instead of moving
                        stuckCounter = 0;
                        return true;
                    } else if (result == 2) {
                        // Should evade - set state and return
                        currentState = State.EVADE;
                        evadeStartRound = rc.getRoundNum();
                        turnsSinceLastCheck = 0;
                        // Find the cat for evade tracking
                        RobotInfo cat = findNearbyCat();
                        if (cat != null) {
                            catLastSeenLoc = getClosestCatTile(cat.getLocation());
                            catLastSeenRound = rc.getRoundNum();
                        }
                        return false; // Don't move, evade instead
                    }
                    // Check if we can still move after actions
                    if (!rc.canMove(d)) {
                        return false;
                    }
                }

                rc.move(d);
                stuckCounter = 0;

                // If we didn't need to turn before moving, we can rotate after moving
                // and potentially take actions with our new view
                if (alreadyFacing) {
                    rotateRandomlyAfterMove();
                }
                return true;
            }

            Direction left = d.rotateLeft();

            if (rc.canMove(left)) {
                boolean alreadyFacing = (rc.getDirection() == left);

                if (!alreadyFacing) {
                    if (!turnToFace(left)) {
                        return false;
                    }
                    int result = handlePostTurnActions(left);
                    if (result == 1) {
                        stuckCounter = 0;
                        return true;
                    } else if (result == 2) {
                        currentState = State.EVADE;
                        evadeStartRound = rc.getRoundNum();
                        turnsSinceLastCheck = 0;
                        RobotInfo cat = findNearbyCat();
                        if (cat != null) {
                            catLastSeenLoc = getClosestCatTile(cat.getLocation());
                            catLastSeenRound = rc.getRoundNum();
                        }
                        return false;
                    }
                    if (!rc.canMove(left)) {
                        return false;
                    }
                }

                rc.move(left);
                stuckCounter = 0;
                return true;
            }

            Direction right = d.rotateRight();

            if (rc.canMove(right)) {
                boolean alreadyFacing = (rc.getDirection() == right);

                if (!alreadyFacing) {
                    if (!turnToFace(right)) {
                        return false;
                    }
                    int result = handlePostTurnActions(right);
                    if (result == 1) {
                        stuckCounter = 0;
                        return true;
                    } else if (result == 2) {
                        currentState = State.EVADE;
                        evadeStartRound = rc.getRoundNum();
                        turnsSinceLastCheck = 0;
                        RobotInfo cat = findNearbyCat();
                        if (cat != null) {
                            catLastSeenLoc = getClosestCatTile(cat.getLocation());
                            catLastSeenRound = rc.getRoundNum();
                        }
                        return false;
                    }
                    if (!rc.canMove(right)) {
                        return false;
                    }
                }

                rc.move(right);
                stuckCounter = 0;
                return true;
            }

            return false;
        }

        static boolean decideDig() throws GameActionException {
            int distSq = ratKingLoc.distanceSquaredTo(myLoc);
            int dist = (int) Math.sqrt(distSq);

            int near = 5;
            int far = (mapWidth * 3) / 4;
            if (far <= near)
                far = near + 1; // safety

            int MAX_PROB = 10;
            int MIN_PROB = 5;

            int p; // probability in percent
            if (dist <= near)
                p = MAX_PROB;
            else if (dist >= far)
                p = MIN_PROB;
            else
                p = MAX_PROB - (dist - near) * (MAX_PROB - MIN_PROB) / (far - near);

            return rng.nextInt(100) < p;
        }

        /**
         * After moving forward without needing to rotate, rotate randomly and
         * try actions again with the new view.
         * Rotation probabilities: 25% each for 90° left, 45° left, 45° right, 90°
         * right.
         */
        private static void rotateRandomlyAfterMove() throws GameActionException {
            if (!rc.canTurn())
                return;

            Direction currentDir = rc.getDirection();
            int roll = rng.nextInt(4);
            Direction newDir;

            switch (roll) {
                case 0: // 90 degrees left (rotate left twice)
                    newDir = currentDir.rotateLeft().rotateLeft();
                    break;
                case 1: // 45 degrees left (rotate left once)
                    newDir = currentDir.rotateLeft();
                    break;
                case 2: // 45 degrees right (rotate right once)
                    newDir = currentDir.rotateRight();
                    break;
                case 3: // 90 degrees right (rotate right twice)
                    newDir = currentDir.rotateRight().rotateRight();
                    break;
                default:
                    newDir = currentDir;
                    break;
            }

            if (rc.canTurn()) {
                rc.turn(newDir);
            }

            // Update myLoc after the move
            myLoc = rc.getLocation();

            // Sense again with new orientation
            senseNearby();

            // Try actions again if action is ready
            if (rc.isActionReady()) {
                tryRatnap();
                tryThrowRat();
                tryAttackNearbyEnemy();
            }
        }

        /**
         * Try to attack any nearby enemy after rotating post-move.
         * This is a simplified attack for the post-move action opportunity.
         */
        private static void tryAttackNearbyEnemy() throws GameActionException {
            RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);
            if (nearbyEnemies.length == 0)
                return;

            // Find best target (prioritize rat kings, then closest)
            RobotInfo bestTarget = null;
            int bestScore = Integer.MIN_VALUE;

            for (RobotInfo enemy : nearbyEnemies) {
                if (enemy.getType() == UnitType.CAT)
                    continue; // Don't attack cats here

                int score = 0;
                if (enemy.getType() == UnitType.RAT_KING) {
                    score += 1000; // Prioritize kings
                }
                score -= myLoc.distanceSquaredTo(enemy.getLocation()); // Prefer closer

                if (score > bestScore) {
                    bestScore = score;
                    bestTarget = enemy;
                }
            }

            if (bestTarget == null)
                return;

            MapLocation attackLoc = bestTarget.getLocation();
            boolean isKing = bestTarget.getType() == UnitType.RAT_KING;

            // For rat king, find best attack tile
            if (isKing) {
                attackLoc = getBestAttackLocationForKing(attackLoc);
            }

            if (attackLoc != null) {
                tryAttackWithCheese(attackLoc, isKing, bestTarget.getHealth());
            }
        }
    }

    // ------------------------------
    // Rat King Specific Logic
    // ------------------------------

    static void initRatKing() throws GameActionException {
        myLoc = rc.getLocation();
        int encodedPos = encodeLocation(myLoc);

        // Write starting position (encoded) - only done once at init by first king
        if (rc.readSharedArray(SHARED_STARTING_KING_POSITION_INDEX) == 0) {
            rc.writeSharedArray(SHARED_STARTING_KING_POSITION_INDEX, encodedPos);
        }

        // Find our king index (first available slot)
        myKingIndex = getKingIndex();

        // Write current position to our slot (if we have a valid slot)
        if (myKingIndex < 5) {
            rc.writeSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + myKingIndex, encodedPos);
        }

        initializePossibleSpawns();

        // Turn to face the ROTATE enemy spawn location
        MapLocation rotateSpawn = possibleEnemySpawns[2]; // Index 2 = ROTATE
        if (rotateSpawn != null) {
            Direction toRotateSpawn = myLoc.directionTo(rotateSpawn);
            if (rc.canTurn()) {
                rc.turn(toRotateSpawn);
            }
        }

        Direction spawnDir = myLoc.directionTo(possibleEnemySpawns[2]);

        Direction[] spawnDirs = {
                spawnDir,
                spawnDir.rotateLeft(),
                spawnDir.rotateRight(),
                spawnDir.rotateLeft().rotateLeft(),
                spawnDir.rotateRight().rotateRight(),
                spawnDir.rotateLeft().rotateLeft().rotateLeft(),
                spawnDir.rotateRight().rotateRight().rotateRight(),
                spawnDir.opposite()
        };

        for (Direction d : spawnDirs) {
            MapLocation spawnLoc = myLoc.add(d).add(d);
            if (rc.canBuildRat(spawnLoc)) {
                rc.buildRat(spawnLoc);
                break;
            }
        }
    }

    static int initialCatTraps = 0;

    static void runRatKing() throws GameActionException {
        myLoc = rc.getLocation();

        int turn = rc.getRoundNum();
        if (rc.isCooperation() && turn > 12) {
            if (initialCatTraps == 0) {
                MapLocation trapLoc = myLoc.translate(0, 2);
                if (rc.canPlaceCatTrap(trapLoc)) {
                    rc.placeCatTrap(trapLoc);
                    initialCatTraps++;
                }
            } else if (initialCatTraps == 1) {
                MapLocation trapLoc = myLoc.translate(2, 0);
                if (rc.canPlaceCatTrap(trapLoc)) {
                    rc.placeCatTrap(trapLoc);
                    initialCatTraps++;
                }
            } else if (initialCatTraps == 2) {
                MapLocation trapLoc = myLoc.translate(0, -2);
                if (rc.canPlaceCatTrap(trapLoc)) {
                    rc.placeCatTrap(trapLoc);
                    initialCatTraps++;
                }
            } else if (initialCatTraps == 3) {
                MapLocation trapLoc = myLoc.translate(-2, 0);
                if (rc.canPlaceCatTrap(trapLoc)) {
                    rc.placeCatTrap(trapLoc);
                    initialCatTraps++;
                }
            }
        }

        senseNearby();
        updateSymmetry();

        kingListenForSqueaks();

        // Check for cat danger
        boolean catDetected = detectCat();

        if (catDetected && catLastSeenLoc != null) {
            // Spawn defense rats toward cat every OTHER turn
            catSpawnToggle = !catSpawnToggle;
            if (catSpawnToggle) {
                Direction toCat = myLoc.directionTo(catLastSeenLoc);
                if (toCat != Direction.CENTER) {
                    // trySpawnInDirection(toCat);
                }
            }

            // Place cat trap toward the cat if cooperation mode is active
            // Rat King is 3x3, so we need to offset by 2 tiles to place trap outside body
            if (rc.isCooperation()) {
                Direction toCat = myLoc.directionTo(catLastSeenLoc);
                kingTryPlaceCatTrapInDirection(toCat);
            }

            // Move away from the cat
            Direction awayFromCat = catLastSeenLoc.directionTo(myLoc);

            if (rc.canMove(awayFromCat)) {
                rc.move(awayFromCat);
            } else if (rc.canMove(awayFromCat.rotateLeft())) {
                rc.move(awayFromCat.rotateLeft());
            } else if (rc.canMove(awayFromCat.rotateRight())) {
                rc.move(awayFromCat.rotateRight());
            }
        }
        // Check for nearby enemy rats (excluding cats)
        RobotInfo nearestEnemyRat = null;
        int nearestEnemyRatDist = Integer.MAX_VALUE;
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);
        for (RobotInfo enemy : nearbyEnemies) {
            int dist = myLoc.distanceSquaredTo(enemy.getLocation());
            if (dist < nearestEnemyRatDist) {
                nearestEnemyRatDist = dist;
                nearestEnemyRat = enemy;
            }
        }

        // Emergency spawn logic: enemy rats nearby = spawn aggressively
        if (nearestEnemyRat != null) {
            Direction toEnemy = myLoc.directionTo(nearestEnemyRat.getLocation());
            if (toEnemy != Direction.CENTER) {
                // Spawn multiple rats toward the threat if we have cheese
                trySpawnInDirection(toEnemy);

                // Place multiple traps toward the enemy
                kingTryPlaceTrapInDirection(toEnemy);
            }
            if (nearestEnemyRatDist <= 16) {
                Direction awayFromEnemy = nearestEnemyRat.getLocation().directionTo(myLoc);
                if (rc.canMove(awayFromEnemy)) {
                    rc.move(awayFromEnemy);
                } else if (rc.canMove(awayFromEnemy.rotateLeft())) {
                    rc.move(awayFromEnemy.rotateLeft());
                } else if (rc.canMove(awayFromEnemy.rotateRight())) {
                    rc.move(awayFromEnemy.rotateRight());
                }
            }
        }

        // Spawn baby rats if estimated alive count is below threshold
        // Prioritize spawning toward the ROTATE enemy spawn location

        int estimatedAliveRats = estimateAliveRats();

        if (estimatedAliveRats < MAX_ALIVE_RATS
                && (rc.getGlobalCheese() >= CHEESE_EMERGENCY_THRESHOLD || estimatedAliveRats < 5)) {

            Direction spawnDir = myLoc.directionTo(possibleEnemySpawns[2]);

            Direction[] spawnDirs = {
                    spawnDir,
                    spawnDir.rotateLeft(),
                    spawnDir.rotateRight(),
                    spawnDir.rotateLeft().rotateLeft(),
                    spawnDir.rotateRight().rotateRight(),
                    spawnDir.rotateLeft().rotateLeft().rotateLeft(),
                    spawnDir.rotateRight().rotateRight().rotateRight(),
                    spawnDir.opposite()
            };

            for (Direction d : spawnDirs) {
                MapLocation spawnLoc = myLoc.add(d).add(d);
                if (rc.canBuildRat(spawnLoc)) {
                    rc.buildRat(spawnLoc);
                    break;
                }
            }
        }

        // Rat king checks for nearby enemies and attacks if found
        RobotInfo target = findBestTarget();
        if (target != null) {
            attackTarget = target;
            doAttackAsKing();
        }

        // Update current position (encoded) in shared array (only if we have a valid
        // slot)
        if (myKingIndex < 5) {
            myLoc = rc.getLocation();
            int encodedCurrentPos = encodeLocation(myLoc);
            rc.writeSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + myKingIndex, encodedCurrentPos);
        }
    }

    /**
     * Try to spawn a baby rat in a given direction, with fallback to adjacent
     * directions.
     * Returns true if a rat was spawned.
     */
    static boolean trySpawnInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER)
            return false;

        MapLocation s;

        switch (dir) {
            case NORTH: {
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case SOUTH: {
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case EAST: {
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case WEST: {
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case NORTHEAST: {
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case SOUTHEAST: {
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case NORTHWEST: {
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            case SOUTHWEST: {
                s = myLoc.translate(-2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canBuildRat(s)) {
                    rc.buildRat(s);
                    return true;
                }
                return false;
            }

            default:
                return false;
        }
    }

    // ------------------------------
    // Baby Rat Specific Logic
    // ------------------------------

    /**
     * Detect potential enemies behind us by checking tiles we cannot sense.
     * Baby rats can only sense forward (facing direction + rotateLeft/rotateRight).
     * This function checks the 5 adjacent tiles behind us that we cannot sense.
     * If a tile is on the map, we can move there, and it's marked as EMPTY in our
     * map,
     * we assume an enemy might be there (since we didn't sense anything there but
     * it's passable).
     * 
     * @return Array of MapLocations where enemies might be hiding behind us
     */
    static MapLocation[] detectPotentialEnemiesBehind() throws GameActionException {
        Direction facing = rc.getDirection();

        // Get the 5 directions we cannot sense (behind us)
        // These are: opposite, opposite.rotateLeft(), opposite.rotateRight(),
        // opposite.rotateLeft().rotateLeft(), opposite.rotateRight().rotateRight()
        Direction behind = facing.rotateLeft().rotateLeft().rotateLeft().rotateLeft(); // Same as opposite()
        Direction behindLeft1 = facing.rotateLeft().rotateLeft().rotateLeft();
        Direction behindRight1 = facing.rotateRight().rotateRight().rotateRight();
        Direction behindLeft2 = facing.rotateLeft().rotateLeft();
        Direction behindRight2 = facing.rotateRight().rotateRight();

        Direction[] unsensedDirections = { behind, behindLeft1, behindRight1, behindLeft2, behindRight2 };

        // We'll collect potential enemy locations (max 5)
        MapLocation[] potentialEnemies = new MapLocation[5];
        int count = 0;

        for (Direction dir : unsensedDirections) {
            // Check if we can move in this direction
            if (!rc.canMove(dir)) {
                continue;
            }

            MapLocation loc = myLoc.add(dir);

            // Check if location is on the map
            if (!rc.onTheMap(loc)) {
                continue;
            }

            // Check the tile value in our map
            Tile tile = map[loc.x][loc.y];

            // If unknown (null), wall, or dirt - ignore it
            if (tile == null || tile == Tile.WALL || tile == Tile.DIRT) {
                continue;
            }

            // If it's EMPTY, assume it might be an enemy
            // (We can move there, it's passable, but we haven't sensed it recently)
            if (tile == Tile.EMPTY) {
                potentialEnemies[count] = loc;
                count++;
            }
        }

        // Create a right-sized array to return
        MapLocation[] result = new MapLocation[count];
        for (int i = 0; i < count; i++) {
            result[i] = potentialEnemies[i];
        }

        // Print out the locations for now
        if (count > 0) {
            System.out.println("Potential enemies behind at: ");
            for (int i = 0; i < count; i++) {
                System.out.println("  " + result[i]);
            }
        }

        return result;
    }

    /**
     * Handle a sneak attack situation - when we take damage but don't see any
     * enemies.
     * This likely means an enemy is behind us where we can't sense.
     * Strategy: Move forward (away from attacker), turn 180 degrees to face them,
     * then try to place a trap or attack.
     * 
     * @return true if we handled a sneak attack situation
     */
    static boolean handleSneakAttack() throws GameActionException {
        int currentHealth = rc.getHealth();

        // Check if we took damage
        if (lastHealth <= 0 || currentHealth >= lastHealth) {
            return false; // No damage taken
        }

        // Check if we can see any enemies
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);
        if (nearbyEnemies.length > 0) {
            return false; // We can see enemies, normal combat logic applies
        }

        // We took damage but can't see enemies - likely attacked from behind!
        MapLocation[] potentialEnemies = detectPotentialEnemiesBehind();

        if (potentialEnemies.length == 0) {
            return false; // No potential enemies detected
        }

        System.out.println("Sneak attack detected! Taking evasive action.");

        // Get our current facing direction
        Direction facing = rc.getDirection();

        // Step 1: Move forward (away from the attacker) without turning
        if (rc.canMove(facing)) {
            rc.move(facing);
            myLoc = rc.getLocation(); // Update our location
        }

        // Step 2: Turn 180 degrees to face the attacker
        // We need to turn twice (each turn is 90 degrees in battlecode)
        Direction opposite = facing.opposite();
        if (rc.canTurn()) {
            rc.turn(opposite);
        }

        // Step 3: Try to throw or ratnap first (higher priority actions)
        tryThrowRat();
        tryRatnap();
        tryThrowRat();

        // Step 4: Try to place a trap in front of us (toward the attacker)
        Direction newFacing = rc.getDirection();
        if (tryPlaceTrapInDirection(newFacing)) {
            System.out.println("Placed trap toward sneaky attacker!");
            return true;
        }

        // Step 5: If we can't place a trap, try to attack the lowest HP enemy we can
        // now see
        RobotInfo[] enemiesNowVisible = rc.senseNearbyRobots(-1, enemyTeam);
        if (enemiesNowVisible.length > 0) {
            // Find the lowest HP enemy
            RobotInfo lowestHpEnemy = null;
            int lowestHp = Integer.MAX_VALUE;

            for (RobotInfo enemy : enemiesNowVisible) {
                if (enemy.getHealth() < lowestHp) {
                    lowestHp = enemy.getHealth();
                    lowestHpEnemy = enemy;
                }
            }

            if (lowestHpEnemy != null) {
                MapLocation enemyLoc = lowestHpEnemy.getLocation();
                boolean isKing = (lowestHpEnemy.getType() == UnitType.RAT_KING);
                if (tryAttackWithCheese(enemyLoc, isKing, lowestHpEnemy.getHealth())) {
                    System.out.println("Attacked sneaky enemy at " + enemyLoc);
                    return true;
                }
            }
        }

        return true; // We handled the situation even if we couldn't attack
    }

    static void initBabyRat() throws GameActionException {
        initializePossibleSpawns();
        currentTargetIndex = rc.getID() % 3;

        if (!mineLocations.isEmpty()) {
            assignedMineIndex = rc.getID() % mineLocations.size();
        }

        currentState = chooseStartingState();
    }

    static void syncSharedArray() throws GameActionException {
        if (determinedSymmetry == Symmetry.UNKNOWN) {
            int sharedSymmetry = rc.readSharedArray(SHARED_SYMMETRY_INDEX);
            if (sharedSymmetry >= 1 && sharedSymmetry <= 3) {
                determinedSymmetry = Symmetry.values()[sharedSymmetry];
                currentTargetIndex = determinedSymmetry.ordinal() - 1;
                destination = null;
            }
        }

        int mineCount = rc.readSharedArray(SHARED_MINE_COUNT_INDEX);

        if (mineCount != prevNumberOfMines) {
            for (int i = 0; i < mineCount; i++) {
                int encodedMine = rc.readSharedArray(SHARED_MINES_START_INDEX + i);
                if (encodedMine != 0) {
                    mineLocations.add(encodedMine);
                }
            }
            prevNumberOfMines = mineCount;
        }
    }

    static void runBabyRat() throws GameActionException {
        myLoc = rc.getLocation();
        syncSharedArray();
        ratKingLoc = findNearestRatKing();

        senseNearby();

        if (rc.isBeingThrown()) {
            Direction throwDirection = prevLoc.directionTo(myLoc);
            if (rc.canTurn()) {
                rc.turn(throwDirection);
            }

            RobotInfo first = rc.senseRobotAtLocation(myLoc.add(throwDirection));
            if (first != null && first.getTeam() == myTeam) {
                rc.disintegrate();
                return;
            }

            RobotInfo second = rc.senseRobotAtLocation(myLoc.add(throwDirection).add(throwDirection));
            if (second != null && second.getTeam() == myTeam) {
                rc.disintegrate();
                return;
            }

            if (rc.canTurn()) {
                rc.turn(throwDirection.opposite());
            }
            senseNearby();
        }

        updateSymmetry();

        // Check for sneak attacks (took damage but can't see enemies)
        // This should be checked early before other actions
        if (handleSneakAttack()) {
            // Update lastHealth at the end of the turn
            lastHealth = rc.getHealth();
            return; // We handled a sneak attack, skip normal turn logic
        }

        tryThrowRat();
        tryRatnap();

        handleTrapPlacement();
        updateState();

        switch (currentState) {
            case FIND_KING:
                doFindKing();
                break;
            case EXPLORE:
                doExplore();
                break;
            case ATTACK:
                doAttack();
                break;
            case RUSH:
                doRush();
                break;
            case EVADE:
                doEvade();
                break;
            case COLLECT:
                doCollect();
                break;
            case RETURN:
                doReturn();
                break;
            default:
                break;
        }

        // Update lastHealth at the end of each turn
        lastHealth = rc.getHealth();
        prevLoc = myLoc;
    }

    public static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rc = rc;

        rng = new Random(rc.getID());

        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();

        map = new Tile[mapWidth][mapHeight];

        visited = new boolean[(mapHeight + VISITED_BLOCK_DIMENSION - 1)
                / VISITED_BLOCK_DIMENSION][(mapWidth + VISITED_BLOCK_DIMENSION - 1) / VISITED_BLOCK_DIMENSION];

        myLoc = rc.getLocation();

        myTeam = rc.getTeam();
        enemyTeam = myTeam.opponent();

        switch (rc.getType()) {
            case RAT_KING:
                initRatKing();
                break;
            default:
                initBabyRat();
                break;
        }

        while (true) {
            try {

                switch (rc.getType()) {
                    case RAT_KING:
                        runRatKing();
                        rc.setIndicatorString("Cheese: " + rc.getGlobalCheese());
                        break;
                    default:
                        //rc.setIndicatorString("Before: " + currentState.toString() + "\n" + "Dest: " + destination);
                        runBabyRat();
                        //rc.setIndicatorString("After: " + currentState.toString() + "\n" + "Dest: " + destination);
                        break;
                }

            } catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ------------------------------
    // Helper Methods
    // ------------------------------

    // Cost formula: BUILD_ROBOT_BASE_COST + floor(aliveRats /
    // NUM_ROBOTS_FOR_COST_INCREASE) * BUILD_ROBOT_COST_INCREASE

    static int estimateAliveRats() throws GameActionException {
        return ((rc.getCurrentRatCost() - GameConstants.BUILD_ROBOT_BASE_COST)
                / GameConstants.BUILD_ROBOT_COST_INCREASE)
                * GameConstants.NUM_ROBOTS_FOR_COST_INCREASE
                + (GameConstants.NUM_ROBOTS_FOR_COST_INCREASE / 2);
    }

    /**
     * Encode a location into a smaller integer (1-1024)
     * We use x/2 and y/2 to fit in the range, with format: (x/2) * 32 + (y/2) + 1
     * Adding 1 ensures 0 represents "no king" / missing value.
     * This works for maps up to 64x64
     */
    static int encodeLocation(MapLocation loc) {
        return (loc.x / 2) * 32 + (loc.y / 2) + 1;
    }

    /**
     * Decode an encoded location back to MapLocation.
     * Subtracts 1 first since encodeLocation adds 1.
     */
    static MapLocation decodeLocation(int encoded) {
        return new MapLocation(((encoded - 1) / 32) * 2, ((encoded - 1) % 32) * 2);
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

    /**
     * Choose the starting state based on the current turn number and cheese level.
     * Most rats should start in FIND_KING to locate enemy quickly.
     * Scouts explore to discover mines early.
     * Later rats or when cheese is low should explore for resources.
     */
    static State chooseStartingState() {
        if (rc.getGlobalCheese() < CHEESE_CRITICAL_THRESHOLD || (rc.getID() % 100) < SCOUT_PERCENTAGE) {
            return State.EXPLORE;
        }

        // Later in game (after initial rush), mix exploration and attacking
        if (rc.getRoundNum() >= 50 && mineLocations.size() < 3) {
            return (rc.getID() % 2 == 0) ? State.FIND_KING : State.EXPLORE;
        }

        return State.FIND_KING;
    }

    /**
     * Find the first available king slot (0-4) by scanning for zeros.
     * Unrolled if statements for performance.
     */
    static int getKingIndex() throws GameActionException {
        if (rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX) == 0) {
            return 0;
        }
        if (rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + 1) == 0) {
            return 1;
        }
        if (rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + 2) == 0) {
            return 2;
        }
        if (rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + 3) == 0) {
            return 3;
        }
        if (rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + 4) == 0) {
            return 4;
        }

        return 5;
    }
}
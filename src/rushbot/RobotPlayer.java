package rushbot;

import battlecode.common.*;

import java.util.Random;

public class RobotPlayer {
    static Random rng;

    static RobotController rc;

    static int mapWidth;
    static int mapHeight;

    static int born;
    static MapLocation myLoc;

    // 0 = unknown/unset, 1 = wall, 2 = mine, 3 = dirt, 4 = known empty
    static int[][] map;

    // 0 = unseen, 1 = seen
    static int[][] visited;

    static MapLocation destination;

    // Oscillation detection
    static MapLocation[] recentPositions = new MapLocation[6];
    static int positionIndex = 0;

    // State machine
    static enum State {
        EXPLORE,
        ATTACK,
        RUSH,
        EVADE,
        COLLECT,
        RETURN
    }
    static State currentState = State.EXPLORE;

    // Enemy rat king location (if known)
    static MapLocation enemyRatKingLoc = null;
    static int enemyRatKingLastSeen = -1;

    // Cat evasion tracking
    static MapLocation catLastSeenLoc = null;
    static int catLastSeenRound = -1;
    static int lastHealth = -1;  // Track health to detect sudden drops (cat scratch from behind)
    static int evadeStartRound = -1;  // When we entered EVADE state
    static final int EVADE_TIMEOUT_TURNS = 12;  // Exit EVADE after this many turns without cat contact
    static final int CAT_SCRATCH_DAMAGE = 50;  // Damage from cat scratch
    static int turnsSinceLastCheck = 0;  // For periodic look-back while evading

    // Max alive rats to maintain (estimated from build cost)
    static final int MAX_ALIVE_RATS = 36;

    // Emergency cheese collection strategy
    static final boolean EMERGENCY_CHEESE_STRATEGY = true;
    static final int CHEESE_EMERGENCY_THRESHOLD = 600;
    static MapLocation ratKingLoc = null;  // Our rat king's location for returning cheese

    // Attack target
    static RobotInfo attackTarget = null;

    // Global array indices for shared communication
    static final int SHARED_KING_X_INDEX = 0;  // Our rat king's starting X position
    static final int SHARED_KING_Y_INDEX = 1;  // Our rat king's starting Y position
    static final int SHARED_SYMMETRY_INDEX = 2; // Determined symmetry (0=unknown, 1=flipX, 2=flipY, 3=flipBoth)

    // Possible enemy spawn locations (3 symmetric possibilities)
    static MapLocation[] possibleEnemySpawns = new MapLocation[3];
    static int currentTargetIndex = -1;  // Which spawn we're currently heading to (-1 = unassigned)
    static int determinedSymmetry = 0;  // 0=unknown, 1=flipX, 2=flipY, 3=flipBoth
    
    // Track which spawns we've visited/checked
    static boolean[] spawnChecked = new boolean[3];
    
    // Flag to track if we've assigned our initial target
    static boolean hasAssignedTarget = false;

    /** 8-way movement directions (no CENTER). */
    static final Direction[] DIRS = new Direction[]{
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST
    };

    // ------------------------------
    // Enemy Spawn Targeting
    // ------------------------------
    
    /**
     * Calculate the symmetric position based on symmetry type
     * 0 = flip X (horizontal mirror)
     * 1 = flip Y (vertical mirror)  
     * 2 = flip both (rotational symmetry)
     */
    static MapLocation getSymmetricLocation(MapLocation loc, int symmetryType) {
        int newX, newY;
        switch (symmetryType) {
            case 0: // Flip X (horizontal mirror)
                newX = mapWidth - 1 - loc.x;
                newY = loc.y;
                break;
            case 1: // Flip Y (vertical mirror)
                newX = loc.x;
                newY = mapHeight - 1 - loc.y;
                break;
            case 2: // Flip both (180 degree rotation)
                newX = mapWidth - 1 - loc.x;
                newY = mapHeight - 1 - loc.y;
                break;
            default:
                newX = loc.x;
                newY = loc.y;
                break;
        }
        return new MapLocation(newX, newY);
    }
    
    /**
     * Initialize the 3 possible enemy spawn locations based on rat king's position.
     * Returns true if initialization was successful, false if king position not yet available.
     */
    static boolean initializePossibleSpawns() throws GameActionException {
        // Read rat king's starting position from shared array
        int kingX = rc.readSharedArray(SHARED_KING_X_INDEX);
        int kingY = rc.readSharedArray(SHARED_KING_Y_INDEX);
        
        if (kingX == 0 && kingY == 0) {
            // Not initialized yet
            return false;
        }
        
        MapLocation kingStartLoc = new MapLocation(kingX, kingY);
        
        // Calculate all 3 possible enemy spawns
        possibleEnemySpawns[0] = getSymmetricLocation(kingStartLoc, 0); // Flip X
        possibleEnemySpawns[1] = getSymmetricLocation(kingStartLoc, 1); // Flip Y
        possibleEnemySpawns[2] = getSymmetricLocation(kingStartLoc, 2); // Flip both
        
        return true;
    }
    
    /**
     * Assign initial target index. Only called once per robot.
     */
    static void assignInitialTarget() {
        if (hasAssignedTarget) return;
        
        // Randomly choose starting target to spread out rats
        currentTargetIndex = rng.nextInt(3);
        hasAssignedTarget = true;
    }
    
    /**
     * Check shared array for determined symmetry.
     * Returns true if symmetry was newly determined (destination should change).
     */
    static boolean checkSymmetryFromSharedArray() throws GameActionException {
        int sharedSymmetry = rc.readSharedArray(SHARED_SYMMETRY_INDEX);
        if (sharedSymmetry > 0 && determinedSymmetry == 0) {
            determinedSymmetry = sharedSymmetry;
            // Set target to the confirmed enemy spawn
            currentTargetIndex = determinedSymmetry - 1; // Convert 1-3 to 0-2
            // Force destination update
            destination = null;
            return true;
        }
        return false;
    }
    
    /**
     * Write determined symmetry to shared array (only rat king can write)
     */
    static void writeSymmetryToSharedArray(int symmetry) throws GameActionException {
        if (rc.getType() == UnitType.RAT_KING) {
            rc.writeSharedArray(SHARED_SYMMETRY_INDEX, symmetry);
        }
    }
    
    /**
     * Try to determine map symmetry based on what we've seen
     * Returns 0 if unknown, 1-3 for determined symmetry type
     */
    static int tryDetermineSymmetry() {
        if (possibleEnemySpawns[0] == null) return 0;
        
        int validCount = 0;
        int lastValid = -1;
        
        for (int i = 0; i < 3; i++) {
            MapLocation spawn = possibleEnemySpawns[i];
            
            // Check if this spawn location is a wall (would be invalid)
            if (visited[spawn.x][spawn.y] == 1 && map[spawn.x][spawn.y] == 1) {
                // This symmetry is impossible - spawn would be in a wall
                spawnChecked[i] = true;
                continue;
            }
            
            // If we've visited this area and didn't find the king, mark as checked
            // (enemyRatKingLoc would be set if we found it)
            if (visited[spawn.x][spawn.y] == 1 && enemyRatKingLoc == null) {
                // We've seen this tile but no king - could still be nearby though
                // Only mark as definitely checked if we're very close and see nothing
                if (myLoc.distanceSquaredTo(spawn) <= 20) {
                    spawnChecked[i] = true;
                    continue;
                }
            }
            
            validCount++;
            lastValid = i;
        }
        
        // If only one valid spawn remains, that's the symmetry
        if (validCount == 1 && lastValid >= 0) {
            return lastValid + 1; // Return 1-3
        }
        
        return 0; // Still unknown
    }
    
    /**
     * Get the current spawn location target.
     * Does NOT change the target - only returns it.
     */
    static MapLocation getCurrentSpawnTarget() {
        if (possibleEnemySpawns[0] == null) return null;
        if (currentTargetIndex < 0) return null;
        
        // If symmetry is determined, always return that spawn
        if (determinedSymmetry > 0) {
            return possibleEnemySpawns[determinedSymmetry - 1];
        }
        
        return possibleEnemySpawns[currentTargetIndex];
    }
    
    /**
     * Move to the next unchecked spawn target.
     * Called only when current target has been confirmed as checked.
     */
    static void advanceToNextTarget() {
        if (possibleEnemySpawns[0] == null) return;
        if (determinedSymmetry > 0) return; // Don't advance if symmetry is known
        
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
     * Marks spawns as checked when we have actually SEEN the spawn tile (via senseNearby).
     * Returns true if we should change our target.
     */
    static boolean updateSpawnChecking() throws GameActionException {
        if (possibleEnemySpawns[0] == null) return false;
        
        boolean shouldChangeTarget = false;
        
        // First, check if we can see enemy king anywhere nearby
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo r : nearbyEnemies) {
            if (r.getType() == UnitType.RAT_KING) {
                enemyRatKingLoc = r.getLocation();
                enemyRatKingLastSeen = rc.getRoundNum();
                // Determine which symmetry this corresponds to
                for (int i = 0; i < 3; i++) {
                    if (possibleEnemySpawns[i].distanceSquaredTo(enemyRatKingLoc) <= 25) {
                        determinedSymmetry = i + 1;
                        writeSymmetryToSharedArray(determinedSymmetry);
                        currentTargetIndex = i;
                        return true; // Target changed to confirmed king location
                    }
                }
            }
        }
        
        // Check our CURRENT target spawn - if we've actually SEEN the tile and no king, move on
        if (currentTargetIndex >= 0 && !spawnChecked[currentTargetIndex]) {
            MapLocation targetSpawn = possibleEnemySpawns[currentTargetIndex];
            
            // Use the visited array - senseNearby() marks tiles we've actually seen
            // This respects cone vision since senseNearby only returns tiles in our vision cone
            if (visited[targetSpawn.x][targetSpawn.y] == 1) {
                // We have seen this spawn tile - if we didn't find king above, it's not here
                spawnChecked[currentTargetIndex] = true;
                advanceToNextTarget();
                shouldChangeTarget = true;
            }
        }
        
        // Try to determine symmetry from map features
        if (determinedSymmetry == 0) {
            int sym = tryDetermineSymmetry();
            if (sym > 0) {
                determinedSymmetry = sym;
                currentTargetIndex = sym - 1;
                writeSymmetryToSharedArray(sym);
                shouldChangeTarget = true;
            }
        }
        
        return shouldChangeTarget;
    }

    // ------------------------------
    // Sensing / exploration target
    // ------------------------------
    public static void senseNearby() throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        for (MapInfo tile : nearby) {
            MapLocation loc = tile.getMapLocation();

            if (tile.isWall()) {
                map[loc.x][loc.y] = 1;
            } else if (tile.hasCheeseMine()) {
                map[loc.x][loc.y] = 2;
            } else if (tile.isDirt()) {
                map[loc.x][loc.y] = 3;
            } else {
                // Mark as empty/passable if we've seen it and it's not special
                if (map[loc.x][loc.y] == 0) {
                    map[loc.x][loc.y] = 4; // 4 = known empty
                }
            }
            visited[loc.x][loc.y] = 1;
        }
    }

    static void chooseDestination() {
        // Target possible enemy spawn locations only
        MapLocation spawnTarget = getCurrentSpawnTarget();
        if (spawnTarget != null) {
            destination = spawnTarget;
            BugNav.setTarget(destination);
        }
    }

    // ------------------------------
    // Oscillation Detection
    // ------------------------------
    static void recordPosition(MapLocation loc) {
        recentPositions[positionIndex] = loc;
        positionIndex = (positionIndex + 1) % recentPositions.length;
    }

    static boolean isOscillating() {
        if (recentPositions[0] == null || recentPositions[2] == null) return false;
        
        MapLocation current = myLoc;
        int matches = 0;
        
        // Check if we've been at the current position multiple times recently
        for (int i = 0; i < recentPositions.length; i++) {
            if (recentPositions[i] != null && recentPositions[i].equals(current)) {
                matches++;
            }
        }
        
        // If we've been at this position 2+ times in the last 6 moves, we're oscillating
        if (matches >= 2) return true;
        
        // Also check for A-B-A-B pattern
        int idx2 = (positionIndex + recentPositions.length - 2) % recentPositions.length;
        int idx4 = (positionIndex + recentPositions.length - 4) % recentPositions.length;
        
        if (recentPositions[idx2] != null && recentPositions[idx4] != null) {
            if (current.equals(recentPositions[idx2]) || current.equals(recentPositions[idx4])) {
                return true;
            }
        }
        
        return false;
    }

    // ------------------------------
    // Digging Logic
    // ------------------------------
    static boolean tryDigToward(MapLocation target) throws GameActionException {
        if (target == null) return false;
        
        Direction dir = myLoc.directionTo(target);
        if (dir == Direction.CENTER) return false;
        
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
        if (!rc.onTheMap(loc)) return false;
        // Check if this location is known dirt
        if (visited[loc.x][loc.y] == 1 && map[loc.x][loc.y] == 3) {
            return true;
        }
        return false;
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
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        Team enemyTeam = rc.getTeam().opponent();
        
        RobotInfo bestTarget = null;
        boolean foundEnemyKing = false;
        int lowestHP = Integer.MAX_VALUE;
        
        for (RobotInfo robot : nearbyRobots) {
            // Skip allies and cats
            if (robot.getTeam() != enemyTeam) continue;
            if (robot.getType() == UnitType.CAT) continue;
            
            // Check if it's an enemy rat king
            if (robot.getType() == UnitType.RAT_KING) {
                // Always prioritize rat king
                if (!foundEnemyKing) {
                    foundEnemyKing = true;
                    bestTarget = robot;
                    lowestHP = robot.getHealth();
                } else if (robot.getHealth() < lowestHP) {
                    bestTarget = robot;
                    lowestHP = robot.getHealth();
                }
                
                // Update enemy rat king location
                enemyRatKingLoc = robot.getLocation();
                enemyRatKingLastSeen = rc.getRoundNum();
            } else if (robot.getType() == UnitType.BABY_RAT && !foundEnemyKing) {
                // Target lowest HP baby rat if no king found
                if (robot.getHealth() < lowestHP) {
                    bestTarget = robot;
                    lowestHP = robot.getHealth();
                }
            }
        }
        
        return bestTarget;
    }

    /**
     * Get all locations adjacent to a rat king's center (for attacking)
     * Rat king occupies a 3x3 area, so we can attack any of the 8 tiles around its center
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
        // We're adjacent if we're within 2 tiles in both directions but not inside the king
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

    // ------------------------------
    // Ratnapping and Throwing Logic
    // ------------------------------
    
    /**
     * Find an enemy baby rat that can be ratnapped.
     * Returns null if no ratnappable rat found.
     */
    static RobotInfo findRatnappableRat() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        Team enemyTeam = rc.getTeam().opponent();
        
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getTeam() != enemyTeam) continue;
            if (robot.getType() != UnitType.BABY_RAT) continue;
            
            // Check if we can ratnap this rat
            if (rc.canCarryRat(robot.getLocation())) {
                return robot;
            }
        }
        return null;
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
        
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.CAT) continue; // Skip cats
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
            if (!rc.onTheMap(throwLoc)) continue;
            if (visited[throwLoc.x][throwLoc.y] == 1 && map[throwLoc.x][throwLoc.y] == 1) continue; // Wall
            
            // Check if tile is passable (not blocked by robot or wall)
            try {
                if (!rc.sensePassability(throwLoc)) continue;
                if (rc.senseRobotAtLocation(throwLoc) != null) continue; // Blocked by robot
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
                if (visited[checkLoc.x][checkLoc.y] == 1 && map[checkLoc.x][checkLoc.y] == 1) {
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
            if (!rc.onTheMap(throwLoc)) continue;
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
        if (rc.getCarrying() == null) return false;
        if (!rc.canThrowRat()) return false;
        
        Direction throwDir = findBestThrowDirection();
        if (throwDir == null) return false;
        
        // Turn to face throw direction
        if (rc.getDirection() != throwDir) {
            if (rc.canTurn()) {
                rc.turn(throwDir);
            } else {
                return false; // Can't turn to throw
            }
        }
        
        // Throw the rat
        if (rc.canThrowRat()) {
            rc.throwRat();
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle ratnap/throw behavior. Should be called at start of turn.
     * Returns true if we performed a ratnap/throw action this turn.
     */
    static boolean handleRatnapping() throws GameActionException {
        // If we're carrying a rat, try to throw it
        if (rc.getCarrying() != null) {
            return tryThrowRat();
        }
        
        // Otherwise, look for rats to ratnap
        return tryRatnap();
    }

    // ------------------------------
    // Trap Placement Logic
    // ------------------------------
    
    /**
     * Count enemy rats in our vision cone (excluding cats).
     */
    static int countEnemyRatsInVision() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
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
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(2, rc.getTeam().opponent());
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
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
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
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
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
        
        if (count == 0) return null;
        
        // Get direction toward average position
        MapLocation avgPos = new MapLocation(myLoc.x + totalDx / count, myLoc.y + totalDy / count);
        return myLoc.directionTo(avgPos);
    }
    
    /**
     * Try to place a rat trap in a specific direction.
     * Returns true if trap was placed.
     */
    static boolean tryPlaceTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;
        
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

    // ------------------------------
    // Squeak Communication
    // ------------------------------
    /**
     * Encode a location into a squeak integer (0-1023)
     * We use x/2 and y/2 to fit in the range, with format: (x/2) * 32 + (y/2)
     * This works for maps up to 64x64
     */
    static int encodeLocation(MapLocation loc) {
        int encodedX = loc.x / 2;
        int encodedY = loc.y / 2;
        return encodedX * 32 + encodedY;
    }

    /**
     * Decode a squeak integer back to a location
     */
    static MapLocation decodeLocation(int encoded) {
        int encodedX = encoded / 32;
        int encodedY = encoded % 32;
        // Multiply back by 2 to get approximate location
        return new MapLocation(encodedX * 2, encodedY * 2);
    }

    /**
     * Squeak the enemy rat king location to nearby allies.
     * DO NOT squeak while evading - cats can hear squeaks!
     */
    static void squeakEnemyKingLocation() throws GameActionException {
        // Don't squeak while evading - cats hear squeaks and will chase us!
        if (currentState == State.EVADE) return;
        
        if (enemyRatKingLoc != null) {
            //int encoded = encodeLocation(enemyRatKingLoc);
            //rc.squeak(encoded);
        }
    }

    /**
     * Listen for squeaks about enemy rat king location
     * If we're further from the enemy king than the sender, re-squeak to propagate info
     */
    static void listenForSqueaks() throws GameActionException {
        int currentRound = rc.getRoundNum();
        boolean shouldResqueak = false;
        
        // Check squeaks from last 5 rounds
        for (int r = Math.max(1, currentRound - 5); r <= currentRound; r++) {
            Message[] messages = rc.readSqueaks(r);
            for (Message msg : messages) {
                // Decode the location
                MapLocation decodedLoc = decodeLocation(msg.getBytes());
                
                // Get sender's location when they squeaked
                MapLocation senderLoc = msg.getSource();
                
                // Update enemy rat king location if this is newer info
                if (enemyRatKingLoc == null || r > enemyRatKingLastSeen) {
                    enemyRatKingLoc = decodedLoc;
                    enemyRatKingLastSeen = r;
                    
                    // Check if we should propagate this squeak
                    // We re-squeak if we're further from the enemy king than the sender
                    // This helps propagate the info to rats even further away from the king
                    if (senderLoc != null) {
                        int senderDistToKing = senderLoc.distanceSquaredTo(decodedLoc);
                        int myDistToKing = myLoc.distanceSquaredTo(decodedLoc);
                        
                        // If we're further away from the king, we should re-squeak 
                        // to propagate to other rats that are even further
                        if (myDistToKing > senderDistToKing) {
                            shouldResqueak = true;
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

    // ------------------------------
    // Cat Detection and Evasion
    // ------------------------------
    
    /**
     * Check if we can see any cats nearby.
     * Returns the closest cat if found, null otherwise.
     */
    static RobotInfo findNearbyCat() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        RobotInfo closestCat = null;
        int closestDist = Integer.MAX_VALUE;
        
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.CAT) {
                int dist = myLoc.distanceSquaredTo(robot.getLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestCat = robot;
                }
            }
        }
        return closestCat;
    }
    
    /**
     * Check for cat presence - either by direct sight or by detecting a scratch from behind.
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
            catLastSeenLoc = cat.getLocation();
            catLastSeenRound = currentRound;
            catDetected = true;
        }
        
        // Method 2: Indirect detection - sudden 50 HP drop (cat scratch from behind)
        // Only check if we didn't already see the cat and health was initialized
        if (!catDetected && lastHealth > 0) {
            int healthLost = lastHealth - currentHealth;
            if (healthLost >= CAT_SCRATCH_DAMAGE) {
                // We got scratched from behind! Cat is likely behind us (opposite our facing direction)
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
        if (currentState != State.EVADE) return false;
        
        int currentRound = rc.getRoundNum();
        
        // Exit if we've been evading for too long without seeing the cat
        if (currentRound - catLastSeenRound >= EVADE_TIMEOUT_TURNS) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Evade behavior - flee from cat.
     */
    static void doEvade() throws GameActionException {
        if (catLastSeenLoc == null) {
            // No cat info, exit evade
            currentState = State.EXPLORE;
            return;
        }
        
        int currentRound = rc.getRoundNum();
        turnsSinceLastCheck++;
        
        // Periodically turn around to check if cat is still there (every 3-4 turns)
        if (turnsSinceLastCheck >= 4) {
            Direction toCat = myLoc.directionTo(catLastSeenLoc);
            if (rc.canTurn() && rc.getDirection() != toCat) {
                rc.turn(toCat);
                turnsSinceLastCheck = 0;
                
                // After turning, check if we can see the cat
                RobotInfo cat = findNearbyCat();
                if (cat != null) {
                    catLastSeenLoc = cat.getLocation();
                    catLastSeenRound = currentRound;
                } else {
                    // Cat not visible - maybe we escaped?
                    // The timeout will handle exiting EVADE
                }
                return;  // Used our turn for checking
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
            Math.max(0, Math.min(mapHeight - 1, fleeTarget.y))
        );
        
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
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT && robot.getID() != rc.getID()) {
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
        if (!EMERGENCY_CHEESE_STRATEGY) return false;
        
        // Only collect if team cheese is below threshold
        int teamCheese = rc.getAllCheese();
        if (teamCheese >= CHEESE_EMERGENCY_THRESHOLD) return false;
        
        // Check if there's cheese nearby
        MapLocation cheeseLoc = findNearbyCheeseLocation();
        if (cheeseLoc == null) return false;
        
        // If no ally baby rats nearby, 100% chance to collect (we're the only hope)
        if (!hasAllyBabyRatsNearby()) {
            return true;
        }
        
        // Otherwise, 50% chance to collect
        return rng.nextBoolean();
    }
    
    /**
     * Get our rat king's location (read from shared array).
     */
    static MapLocation getRatKingLocation() throws GameActionException {
        int kingX = rc.readSharedArray(SHARED_KING_X_INDEX);
        int kingY = rc.readSharedArray(SHARED_KING_Y_INDEX);
        if (kingX == 0 && kingY == 0) return null;
        return new MapLocation(kingX, kingY);
    }
    
    /**
     * Collect cheese behavior - pick up nearby cheese.
     */
    static void doCollect() throws GameActionException {
        // Find cheese to collect
        MapLocation cheeseLoc = findNearbyCheeseLocation();
        
        if (cheeseLoc == null) {
            // No more cheese nearby, switch to RETURN mode if we have cheese
            if (rc.getRawCheese() > 0) {
                currentState = State.RETURN;
                return;
            } else {
                // No cheese found and we have none, go back to exploring
                currentState = State.EXPLORE;
                return;
            }
        }
        
        // Try to pick up cheese if adjacent
        if (rc.canPickUpCheese(cheeseLoc)) {
            rc.pickUpCheese(cheeseLoc);
            return;
        }
        
        // Move toward cheese
        BugNav.goTo(cheeseLoc);
    }
    
    /**
     * Return to rat king and deliver cheese.
     */
    static void doReturn() throws GameActionException {
        // Get rat king location
        ratKingLoc = getRatKingLocation();
        
        if (ratKingLoc == null) {
            // Can't find king, go back to exploring
            currentState = State.EXPLORE;
            return;
        }
        
        // If we have no cheese, go back to exploring
        int myCheese = rc.getRawCheese();
        if (myCheese <= 0) {
            currentState = State.EXPLORE;
            return;
        }
        
        // Try to transfer cheese to king if adjacent
        // King is 3x3, check all tiles around center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation kingTile = ratKingLoc.translate(dx, dy);
                if (rc.canTransferCheese(kingTile, myCheese)) {
                    rc.transferCheese(kingTile, myCheese);
                    // Successfully delivered, go back to exploring
                    currentState = State.EXPLORE;
                    return;
                }
            }
        }
        
        // Move toward rat king
        BugNav.goTo(ratKingLoc);
    }

    // ------------------------------
    // State Machine Logic
    // ------------------------------
    static void updateState() throws GameActionException {
        int currentRound = rc.getRoundNum();
        
        // First priority: Check for cat presence
        boolean catDetected = detectCat();
        
        // If cat detected, enter EVADE mode
        if (catDetected) {
            if (currentState != State.EVADE) {
                evadeStartRound = currentRound;
                turnsSinceLastCheck = 0;
            }
            currentState = State.EVADE;
            return;  // Don't process other state logic when evading
        }
        
        // If currently evading, check if we should continue or exit
        if (currentState == State.EVADE) {
            if (shouldExitEvade()) {
                currentState = State.EXPLORE;
                catLastSeenLoc = null;
                evadeStartRound = -1;
                turnsSinceLastCheck = 0;
            } else {
                return;  // Stay in EVADE mode
            }
        }
        
        // Check if we should enter COLLECT mode (emergency cheese strategy)
        // Only check if not already collecting/returning
        if (currentState != State.COLLECT && currentState != State.RETURN) {
            if (shouldEnterCollectMode()) {
                currentState = State.COLLECT;
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
        
        if (target != null) {
            // Enemy detected nearby - enter ATTACK mode
            currentState = State.ATTACK;
        } else if (enemyRatKingLoc != null) {
            // We know where enemy king is - check distance
            int distToKing = myLoc.distanceSquaredTo(enemyRatKingLoc);
            if (distToKing <= 25) {
                // Close enough, enter ATTACK mode (will search for target)
                currentState = State.ATTACK;
            } else {
                // Far away, RUSH toward king
                currentState = State.RUSH;
            }
        } else {
            // No intel, explore
            currentState = State.EXPLORE;
        }
    }

    // ------------------------------
    // Attack Logic
    // ------------------------------
    
    /**
     * Determine how much cheese to spend on an attack.
     * Returns 2 if we have >500 cheese, or >100 cheese and attacking rat king.
     * Returns 0 otherwise.
     */
    static int getCheeseForAttack(boolean isAttackingKing) throws GameActionException {
        int cheese = rc.getAllCheese();
        
        // Spend 2 cheese if we have lots of cheese
        if (cheese > 500) {
            return 2;
        }
        
        // Spend 2 cheese if attacking rat king and have decent cheese
        if (isAttackingKing && cheese > 100) {
            return 2;
        }
        
        return 0;
    }
    
    /**
     * Try to attack a location, using cheese for extra damage if appropriate.
     * Returns true if attack was performed.
     */
    static boolean tryAttackWithCheese(MapLocation attackLoc, boolean isKing) throws GameActionException {
        int cheeseToUse = getCheeseForAttack(isKing);
        
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
                // Move toward known king location
                BugNav.goTo(enemyRatKingLoc);
            } else {
                // Lost the target, go back to exploring
                currentState = State.EXPLORE;
            }
            return;
        }
        
        MapLocation targetLoc = attackTarget.getLocation();
        boolean isKing = attackTarget.getType() == UnitType.RAT_KING;
        
        // If it's a rat king, squeak its location
        if (isKing) {
            squeakEnemyKingLocation();
        }
        
        // Determine attack location
        MapLocation attackLoc;
        if (isKing) {
            // For rat king, we need to attack a tile adjacent to its center
            attackLoc = getBestAttackLocationForKing(targetLoc);
        } else {
            attackLoc = targetLoc;
        }
        
        if (attackLoc == null) return;
        
        // Check if we can attack directly (with cheese if appropriate)
        if (tryAttackWithCheese(attackLoc, isKing)) {
            return;
        }
        
        // Need to get closer - turn toward target first
        Direction dirToTarget = myLoc.directionTo(attackLoc);
        if (dirToTarget != Direction.CENTER && dirToTarget != rc.getDirection()) {
            if (rc.canTurn()) {
                rc.turn(dirToTarget);
            }
        }
        
        // Try to move toward target
        if (rc.isMovementReady()) {
            if (myLoc.isAdjacentTo(attackLoc)) {
                // Already adjacent, just need to face and attack
                tryAttackWithCheese(attackLoc, isKing);
            } else {
                // Move toward target
                BugNav.goTo(attackLoc);
                
                // After moving, try to attack if possible
                tryAttackWithCheese(attackLoc, isKing);
            }
        }
    }

    // ------------------------------
    // Rush Logic
    // ------------------------------
    static void doRush() throws GameActionException {
        if (enemyRatKingLoc == null) {
            currentState = State.EXPLORE;
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
    // Explore Logic (Baby Rats only)
    // ------------------------------
    static void doExplore() throws GameActionException {
        // Check shared array for symmetry updates (may change our target)
        boolean symmetryChanged = checkSymmetryFromSharedArray();
        
        // Update spawn checking based on our position (returns true if target changed)
        boolean targetChanged = updateSpawnChecking();
        
        // Only update destination if we don't have one, or if target explicitly changed
        if (destination == null || targetChanged || symmetryChanged) {
            MapLocation targetSpawn = getCurrentSpawnTarget();
            if (targetSpawn != null) {
                destination = targetSpawn;
                BugNav.setTarget(destination);
            }
        }

        // Move using BugNav toward persistent destination
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
        private static int bugStartDist = Integer.MAX_VALUE;  // Distance when we started bugging
        private static Direction bugWallDir = null;  // Direction of the wall we're following
        
        // Stuck detection
        private static MapLocation lastPosition = null;
        private static int stuckCounter = 0;

        static void setTarget(MapLocation newTarget) {
            if (newTarget == null) return;
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
            if (dest == null) return;
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

            if (!rc.isMovementReady()) return;

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
                    return;  // Greedy worked
                }
                
                // Greedy failed - start bugging
                bugging = true;
                bugStartDist = distToDest;
                bugWallDir = dirToDest;  // The wall is in the direction we wanted to go
                // Choose bug direction based on robot ID for variety
                bugClockwise = (rc.getID() % 2 == 0);
            }
            
            // We're in bug mode - follow the wall
            if (bugging) {
                // Check if we can exit bug mode: 
                // We can move toward target AND we're closer than when we started bugging
                if (canMoveDir(dirToDest) && distToDest < bugStartDist) {
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
            if (bugWallDir == null) return false;
            
            // Start from the direction pointing INTO the wall, then rotate to find open space
            // We rotate away from the wall to find the first open direction
            Direction d = bugClockwise ? bugWallDir.rotateRight() : bugWallDir.rotateLeft();
            
            for (int i = 0; i < 8; i++) {
                if (tryMoveDir(d)) {
                    // We moved in direction d
                    // Update wall direction: the wall is now roughly opposite to where we came from,
                    // rotated toward the wall side
                    bugWallDir = bugClockwise ? d.rotateLeft().rotateLeft() : d.rotateRight().rotateRight();
                    return true;
                }
                // Rotate to try next direction
                d = bugClockwise ? d.rotateRight() : d.rotateLeft();
            }
            
            return false;  // Completely stuck
        }
        
        /**
         * Check if we can move in a direction (without moving)
         */
        private static boolean canMoveDir(Direction d) throws GameActionException {
            if (d == null || d == Direction.CENTER) return false;
            MapLocation next = rc.getLocation().add(d);
            if (!rc.onTheMap(next)) return false;
            // Avoid known walls
            if (visited[next.x][next.y] == 1 && map[next.x][next.y] == 1) return false;
            return rc.canMove(d);
        }
        
        /**
         * Turn to face a direction if not already facing it.
         * Returns true if we're now facing the direction (either already were, or successfully turned).
         */
        private static boolean turnToFace(Direction d) throws GameActionException {
            if (d == null || d == Direction.CENTER) return true;
            
            Direction currentDir = rc.getDirection();
            if (currentDir == d) {
                return true;  // Already facing the right direction
            }
            
            if (rc.canTurn()) {
                rc.turn(d);
                return true;
            }
            
            return false;  // Couldn't turn
        }

        /**
         * Try to move in a direction, including adjacent directions if direct fails.
         * Will turn to face the movement direction first to avoid movement penalty.
         */
        private static boolean tryMoveDir(Direction d) throws GameActionException {
            if (d == null || d == Direction.CENTER) return false;
            
            // Try exact direction first
            if (canMoveDir(d)) {
                turnToFace(d);  // Turn first to avoid penalty (can turn and move same turn)
                rc.move(d);
                stuckCounter = 0;
                return true;
            }
            
            // Try rotating slightly left and right
            Direction left = d.rotateLeft();
            Direction right = d.rotateRight();
            
            if (canMoveDir(left)) {
                turnToFace(left);
                rc.move(left);
                stuckCounter = 0;
                return true;
            }
            
            if (canMoveDir(right)) {
                turnToFace(right);
                rc.move(right);
                stuckCounter = 0;
                return true;
            }
            
            return false;
        }
    }

    // ------------------------------
    // Rat King Specific Logic
    // ------------------------------
    static boolean hasWrittenStartPos = false;
    
    /**
     * Estimate the number of alive rats based on build cost.
     * Cost formula: BUILD_ROBOT_BASE_COST + floor(aliveRats / NUM_ROBOTS_FOR_COST_INCREASE) * BUILD_ROBOT_COST_INCREASE
     * Which is: 10 + floor(aliveRats / 4) * 10
     * We estimate in the middle of the possible range (add 2).
     */
    static int estimateAliveRats() throws GameActionException {
        int cost = rc.getCurrentRatCost();
        // Reverse the formula: cost = 10 + k * 10, so k = (cost - 10) / 10
        // aliveRats is in range [k*4, k*4+3], we estimate middle as k*4 + 2
        int k = (cost - GameConstants.BUILD_ROBOT_BASE_COST) / GameConstants.BUILD_ROBOT_COST_INCREASE;
        return k * GameConstants.NUM_ROBOTS_FOR_COST_INCREASE + 2;
    }
    
    static void runRatKing() throws GameActionException {
        myLoc = rc.getLocation();
        recordPosition(myLoc);

        // Write our starting position to shared array (only once at start)
        if (!hasWrittenStartPos) {
            rc.writeSharedArray(SHARED_KING_X_INDEX, myLoc.x);
            rc.writeSharedArray(SHARED_KING_Y_INDEX, myLoc.y);
            hasWrittenStartPos = true;
        }

        senseNearby();
        
        // Check for cat danger - rat king should evade cats too
        boolean catDetected = detectCat();
        
        if (catDetected && catLastSeenLoc != null) {
            // Spawn a baby rat toward the cat to distract/attack it (ignore baby rat limit)
            Direction toCat = myLoc.directionTo(catLastSeenLoc);
            if (toCat != Direction.CENTER) {
                // Try to spawn in the direction of the cat
                MapLocation spawnLoc = myLoc.add(toCat).add(toCat);
                if (rc.canBuildRat(spawnLoc)) {
                    rc.buildRat(spawnLoc);
                } else {
                    // Try adjacent directions toward the cat
                    Direction left = toCat.rotateLeft();
                    Direction right = toCat.rotateRight();
                    MapLocation leftSpawn = myLoc.add(left).add(left);
                    MapLocation rightSpawn = myLoc.add(right).add(right);
                    if (rc.canBuildRat(leftSpawn)) {
                        rc.buildRat(leftSpawn);
                    } else if (rc.canBuildRat(rightSpawn)) {
                        rc.buildRat(rightSpawn);
                    }
                }
            }
            
            // Move away from the cat
            Direction awayFromCat = catLastSeenLoc.directionTo(myLoc);
            if (awayFromCat == Direction.CENTER) {
                awayFromCat = DIRS[rng.nextInt(8)];
            }
            
            // Calculate flee target
            MapLocation fleeTarget = myLoc.add(awayFromCat).add(awayFromCat).add(awayFromCat);
            fleeTarget = new MapLocation(
                Math.max(0, Math.min(mapWidth - 1, fleeTarget.x)),
                Math.max(0, Math.min(mapHeight - 1, fleeTarget.y))
            );
            
            BugNav.goTo(fleeTarget);
            
            // Update shared array with new position after moving
            myLoc = rc.getLocation();
            rc.writeSharedArray(SHARED_KING_X_INDEX, myLoc.x);
            rc.writeSharedArray(SHARED_KING_Y_INDEX, myLoc.y);
            return;  // Skip normal behavior when evading
        }
        
        // Spawn baby rats if estimated alive count is below threshold
        int estimatedAlive = estimateAliveRats();
        if (estimatedAlive < MAX_ALIVE_RATS) {
            for (Direction d : DIRS) {
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
            doAttack();
        }
        // Rat king stays in place, does not explore or rush
    }

    // ------------------------------
    // Baby Rat Specific Logic
    // ------------------------------
    static void runBabyRat() throws GameActionException {
        myLoc = rc.getLocation();
        recordPosition(myLoc);

        // Initialize possible enemy spawns if not done yet
        if (possibleEnemySpawns[0] == null) {
            if (!initializePossibleSpawns()) {
                // King position not available yet, skip this turn
                return;
            }
        }
        
        // Assign initial target (only happens once per robot)
        if (!hasAssignedTarget) {
            assignInitialTarget();
        }

        senseNearby();
        
        // Priority 1: Handle ratnapping - if carrying, throw; if can ratnap, do it
        if (handleRatnapping()) {
            // We performed a ratnap action, but can still move/do other things
        }
        
        // Priority 2: Handle trap placement (if enemy rat king nearby or 3+ enemy rats)
        handleTrapPlacement();
        
        // Update state and act
        updateState();
        
        switch (currentState) {
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
        }
    }

    // ------------------------------
    // Main
    // ------------------------------
    public static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rc = rc;

        rng = new Random(rc.getID());

        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();

        visited = new int[mapWidth][mapHeight];
        map = new int[mapWidth][mapHeight];

        born = rc.getRoundNum();
        System.out.println("I'm alive on round " + born);

        myLoc = rc.getLocation();

        while (true) {
            try {
                UnitType unitType = rc.getType();
                
                if (unitType == UnitType.RAT_KING) {
                    runRatKing();
                } else if (unitType == UnitType.BABY_RAT) {
                    runBabyRat();
                }
                // Note: We don't control CATs, they are neutral

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
}
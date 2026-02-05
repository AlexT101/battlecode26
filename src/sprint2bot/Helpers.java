package sprint2bot;

import static sprint2bot.Globals.*;

import battlecode.common.*;

public class Helpers {

    static boolean catCanSeeLocation(RobotInfo cat, MapLocation targetLoc) {
        Direction oppositeFacing = targetLoc.directionTo(cat.getLocation());
        Direction catDir = cat.getDirection();

        if (oppositeFacing == null || catDir == null) {
            return false;
        }

        return oppositeFacing == catDir || oppositeFacing == catDir.rotateLeft() || oppositeFacing == catDir.rotateRight();
    }

    static boolean tryPickUpCheese(MapLocation loc) throws GameActionException {
        if (rc.canPickUpCheese(loc)) {
            rc.pickUpCheese(loc);
            return true;
        }
        return false;
    }

    static boolean kingTryDigToward(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER || !rc.isActionReady() || rc.getDirt() <= 0) {
            return false;
        }

        MapLocation s;

        switch (dir) {
            case NORTH: {
                s = myLoc.translate(0, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case SOUTH: {
                s = myLoc.translate(0, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case EAST: {
                s = myLoc.translate(2, 0);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case WEST: {
                s = myLoc.translate(-2, 0);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case NORTHEAST: {
                s = myLoc.translate(2, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case NORTHWEST: {
                s = myLoc.translate(-2, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case SOUTHEAST: {
                s = myLoc.translate(2, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            case SOUTHWEST: {
                s = myLoc.translate(-2, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canRemoveDirt(s)) {
                    rc.removeDirt(s);
                    return true;
                }
                return false;
            }

            default:
                return false;
        }
    }

    static boolean tryDigToward(MapLocation target) throws GameActionException {
        Direction dir = myLoc.directionTo(target);

        Direction[] digsToTry = {
                dir,
                dir.rotateLeft(),
                dir.rotateRight()
        };

        for (Direction d : digsToTry) {
            MapLocation digLoc = myLoc.add(d);
            if (rc.canRemoveDirt(digLoc)) {
                rc.removeDirt(digLoc);
                return true;
            }

        }

        return false;
    }

    static int countRats(RobotInfo[] robots) throws GameActionException {
        int count = 0;
        for (RobotInfo robot : robots) {
            if (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING) {
                count++;
            }
        }
        return count;
    }

    static int getDistanceToRatKing(MapLocation kingCenter) {
        return myLoc.distanceSquaredTo(kingCenter);
    }

    static boolean isAdjacentToRatKing(MapLocation kingCenter) {
        return getDistanceToRatKing(kingCenter) <= 8;
    }

    static boolean isWithinOneTileFromRatKing(MapLocation kingCenter) {
        return getDistanceToRatKing(kingCenter) <= 13;
    }

    static RobotInfo findVisibleEnemyKing() throws GameActionException {
        for (RobotInfo robot : sensedEnemies) {
            if (robot.getType() == UnitType.RAT_KING) {
                return robot;
            }
        }
        return null;
    }

    static boolean isLocationAdjacentToCat(MapLocation loc, MapLocation catLoc) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                MapLocation catTile = catLoc.translate(dx, dy);
                if (loc.isWithinDistanceSquared(catTile, 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isLocationOneTileFromCat(MapLocation loc, MapLocation catLoc) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                MapLocation catTile = catLoc.translate(dx, dy);
                int distSq = loc.distanceSquaredTo(catTile);
                if (distSq >= 3 && distSq <= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isLocationAdjacentToEnemyRat(MapLocation loc, RobotInfo[] enemies) {
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                if (loc.isWithinDistanceSquared(enemy.getLocation(), 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    static MapLocation[] getRatKingAttackLocations(MapLocation kingCenter) {
        MapLocation[] locs = new MapLocation[8];
        int i = 0;
        for (Direction d : DIRS) {
            locs[i++] = kingCenter.add(d);
        }
        return locs;
    }

    static MapLocation getClosestKingTile(MapLocation kingCenter) {
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

    static boolean isLocationAdjacentToKing(MapLocation loc, MapLocation kingCenter) {
        return loc.isWithinDistanceSquared(kingCenter, 8)
                && !loc.isWithinDistanceSquared(kingCenter, 2);
    }

    static boolean isLocationAdjacentToEnemyKing(MapLocation loc, RobotInfo[] enemies) {
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.RAT_KING && isLocationAdjacentToKing(loc, enemy.getLocation())) {
                return true;
            }
        }
        return false;
    }

    static int countAdjacentEnemyBabyRats(MapLocation loc, RobotInfo[] enemies) {
        int count = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                if (loc.isWithinDistanceSquared(enemy.getLocation(), 2)) {
                    count++;
                }
            }
        }
        return count;
    }

    static int countOneTileAwayEnemyRats(MapLocation loc, RobotInfo[] enemies) {
        int count = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                int distSq = loc.distanceSquaredTo(enemy.getLocation());
                if (distSq >= 3 && distSq <= 8) {
                    count++;
                }
            }
        }
        return count;
    }

    static int getAdjacentCheeseAmount(MapLocation loc) throws GameActionException {
        int total = 0;
        for (Direction d : DIRS) {
            MapLocation adj = loc.add(d);
            if (rc.onTheMap(adj) && rc.canSenseLocation(adj)) {
                MapInfo info = rc.senseMapInfo(adj);
                total += info.getCheeseAmount();
            }
        }
        if (rc.canSenseLocation(loc)) {
            MapInfo info = rc.senseMapInfo(loc);
            total += info.getCheeseAmount();
        }
        return total;
    }

    /**
     * Get all 4 tiles of a cat's 2x2 body.
     * Cat's location is the tile with lowest x,y coordinates.
     * Cat occupies: (x,y), (x+1,y), (x,y+1), (x+1,y+1)
     */
    static MapLocation[] getCatBodyTiles(MapLocation catLoc) {
        return UnitType.CAT.getAllTypeLocations(catLoc);
    }

    static MapLocation getClosestCatTile(MapLocation catCenter) {
        MapLocation closest = catCenter;
        int closestDist = myLoc.distanceSquaredTo(catCenter);

        MapLocation[] catTiles = getCatBodyTiles(catCenter);

        for (MapLocation tile : catTiles) {
            int dist = myLoc.distanceSquaredTo(tile);
            if (dist < closestDist) {
                closestDist = dist;
                closest = tile;
            }
        }

        return closest;
    }

    // Cost formula: BUILD_ROBOT_BASE_COST + floor(aliveRats /
    // NUM_ROBOTS_FOR_COST_INCREASE) * BUILD_ROBOT_COST_INCREASE

    static int estimateAliveRats() throws GameActionException {
        return ((rc.getCurrentRatCost() - GameConstants.BUILD_ROBOT_BASE_COST)
                / GameConstants.BUILD_ROBOT_COST_INCREASE)
                * GameConstants.NUM_ROBOTS_FOR_COST_INCREASE
                + (GameConstants.NUM_ROBOTS_FOR_COST_INCREASE / 2);
    }

    /**
     * Calculate the minimum cheese needed to get a desired amount of extra damage.
     * Extra damage formula: ceil(sqrt(cheese))
     * So for X extra damage, we need minimum cheese where ceil(sqrt(cheese)) >= X
     * This means cheese >= (X-1)^2 + 1 for X > 0
     * 
     * @param extraDamage The desired extra damage (0-5 typically)
     * @return The minimum cheese needed to achieve that extra damage
     */
    static int getCheeseForExtraDamage(int extraDamage) {
        if (extraDamage <= 0) {
            return 0;
        }
        // For X extra damage, minimum cheese is (X-1)^2 + 1
        // Examples: 1 damage -> 1 cheese, 2 damage -> 2 cheese, 3 damage -> 5 cheese
        return (extraDamage - 1) * (extraDamage - 1) + 1;
    }

    /**
     * Calculate extra damage from a given amount of cheese.
     * 
     * @param cheese The amount of cheese to spend
     * @return The extra damage that cheese would provide
     */
    static int getExtraDamageFromCheese(int cheese) {
        if (cheese <= 0) {
            return 0;
        }
        return (int) Math.ceil(Math.sqrt(cheese));
    }

    /**
     * Calculate turns to kill an enemy with a given damage per hit.
     * 
     * @param health       Enemy health
     * @param damagePerHit Damage dealt per turn
     * @return Number of turns to kill
     */
    static int turnsToKill(int health, int damagePerHit) {
        return (health + damagePerHit - 1) / damagePerHit; // Ceiling division
    }

    /**
     * Check if we are currently carrying an ally rat.
     */
    static boolean isCarryingAlly() throws GameActionException {
        RobotInfo carried = rc.getCarrying();
        if (carried == null)
            return false;
        return carried.getTeam() == myTeam;
    }

    /**
     * Try to drop a carried ally safely when enemies are sensed.
     * Priority order: 2x left, 2x right, behind directions, then front.
     * Returns true if ally was dropped.
     */
    static boolean tryDropAllyInCombat() throws GameActionException {
        if (!isCarryingAlly())
            return false;

        Direction facing = rc.getDirection();
        if (facing == null)
            return false;

        // Priority order for drop directions
        Direction[] dropPriority = {
                facing.rotateLeft().rotateLeft(), // 2x left (90 degrees left)
                facing.rotateRight().rotateRight(), // 2x right (90 degrees right)
                facing.opposite(), // directly behind
                facing.opposite().rotateLeft(), // behind-left
                facing.opposite().rotateRight(), // behind-right
                facing.rotateLeft(), // front-left
                facing.rotateRight(), // front-right
                facing // directly in front (last resort)
        };

        for (Direction dropDir : dropPriority) {
            if (rc.canDropRat(dropDir)) {
                rc.dropRat(dropDir);
                return true;
            }
        }

        return false;
    }

    /**
     * Check if target enemy is ratnappable from a given location.
     * Ratnappable if: 1) target HP < our HP, 2) target not facing our location
     * (3-dir cone)
     */
    static boolean isRatnappable(RobotInfo target, MapLocation fromLoc) {
        if (target == null || fromLoc == null)
            return false;
        if (target.getType() != UnitType.BABY_RAT)
            return false;

        // HP condition: target must have lower HP
        if (target.getHealth() >= rc.getHealth())
            return false;

        // Facing condition: check if fromLoc is in target's 3-direction vision cone
        Direction targetFacing = target.getDirection();
        if (targetFacing == null)
            return true; // If no direction, consider ratnappable

        Direction toUs = target.getLocation().directionTo(fromLoc);
        if (toUs == null || toUs == Direction.CENTER)
            return true;

        // Check if we're in their 3-direction cone (facing, left, right)
        if (toUs == targetFacing ||
                toUs == targetFacing.rotateLeft() ||
                toUs == targetFacing.rotateRight()) {
            return false; // We're in their vision cone, not safe
        }

        return true; // We're outside their vision cone, safe to ratnap
    }

    /**
     * Find the lowest HP ally rat nearby (for sacrificial throwing)
     */
    static RobotInfo findLowestHPAlly() {
        RobotInfo lowestHPAlly = null;
        int lowestHP = Integer.MAX_VALUE;

        for (RobotInfo ally : sensedAllies) {
            if (ally.getType() == UnitType.BABY_RAT) {
                int hp = ally.getHealth();
                if (hp < lowestHP) {
                    lowestHP = hp;
                    lowestHPAlly = ally;
                }
            }
        }
        return lowestHPAlly;
    }

    /**
     * Find a cat that is adjacent to an ally king location
     */
    static RobotInfo findCatAdjacentToKing(MapLocation kingLoc) {
        if (kingLoc == null)
            return null;

        for (RobotInfo cat : sensedCats) {
            MapLocation catLoc = cat.getLocation();
            // Cat is 2x2, check if any cat tile is adjacent to king (within dist 8)
            MapLocation[] catTiles = getCatBodyTiles(catLoc);
            for (MapLocation tile : catTiles) {
                if (tile.distanceSquaredTo(kingLoc) <= 8) {
                    return cat;
                }
            }
        }
        return null;
    }

    /**
     * Check if there's an empty tile in front of us in a direction
     */
    static boolean hasEmptyTileInFront(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER)
            return false;
        MapLocation frontTile = myLoc.add(dir);
        if (!rc.onTheMap(frontTile))
            return false;
        if (!rc.canSenseLocation(frontTile))
            return false;

        RobotInfo robot = rc.senseRobotAtLocation(frontTile);
        if (robot != null)
            return false;

        MapInfo info = rc.senseMapInfo(frontTile);
        return !info.isWall() && !info.isDirt();
    }

    /**
     * Find an ally at a mine location that we can pick up for courier duty.
     * Since we can't directly see another robot's cheese, we assume allies at mines
     * have cheese.
     */
    static RobotInfo findWealthyAlly() {
        for (RobotInfo ally : sensedAllies) {
            if (ally.getType() != UnitType.BABY_RAT)
                continue;
            if (ally.getRawCheeseAmount() >= 80 && myLoc.isAdjacentTo(ally.getLocation())) {
                return ally;
            }
        }
        return null;
    }

    static final int[][] buildOffsets = {
            { 0, 2 }, { -1, 2 }, { 1, 2 }, { -2, 2 }, { 2, 2 },
            { -2, 1 }, { 2, 1 },
            { -2, 0 }, { 2, 0 },
            { -2, -1 }, { 2, -1 },
            { 0, -2 }, { -1, -2 }, { 1, -2 }, { -2, -2 }, { 2, -2 }
    };

    static boolean trySpawnInDir(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER || !rc.isActionReady()) {
            return false;
        }

        MapLocation goal = myLoc.add(dir);

        if (rc.canBuildRat(goal)) {
            rc.buildRat(goal);
            return true;
        }

        int bestDist = Integer.MAX_VALUE;
        MapLocation bestLoc = null;

        for (int[] offset : buildOffsets) {
            int newX = goal.x + offset[0];
            int newY = goal.y + offset[1];
            MapLocation tryLoc = new MapLocation(newX, newY);
            int dist = tryLoc.distanceSquaredTo(goal);
            if (dist < bestDist && rc.canBuildRat(tryLoc)) {
                bestDist = dist;
                bestLoc = tryLoc;
            }
        }

        if (bestLoc != null) {
            rc.buildRat(bestLoc);
            return true;
        }

        return false;
    }

    static boolean kingTryPlaceDirtInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER || !rc.isActionReady() || rc.getDirt() <= 0) {
            return false;
        }

        MapLocation s;

        switch (dir) {
            case NORTH: {
                s = myLoc.translate(0, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case SOUTH: {
                s = myLoc.translate(0, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case EAST: {
                s = myLoc.translate(2, 0);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case WEST: {
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case NORTHEAST: {
                s = myLoc.translate(2, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case NORTHWEST: {
                s = myLoc.translate(-2, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, 2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case SOUTHEAST: {
                s = myLoc.translate(2, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(1, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, -1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(2, 0);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            case SOUTHWEST: {
                s = myLoc.translate(-2, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-1, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, -1);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(0, -2);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                s = myLoc.translate(-2, 0);
                if (rc.canPlaceDirt(s)) {
                    rc.placeDirt(s);
                    return true;
                }
                return false;
            }

            default:
                return false;
        }
    }

    static boolean kingTryPlaceCatTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER || !rc.isActionReady()) {
            return false;
        }

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
                return false;
            }

            default:
                return false;
        }
    }

    static boolean kingTryPlaceRatTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER || !rc.isActionReady()) {
            return false;
        }

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
                return false;
            }

            default:
                return false;
        }
    }
}

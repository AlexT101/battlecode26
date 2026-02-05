package finalsbot;

import battlecode.common.*;
import finalsbot.Globals.State;
import finalsbot.Map.Symmetry;
import finalsbot.Map.Tile;

import static finalsbot.Comms.*;
import static finalsbot.Globals.*;
import static finalsbot.Helpers.*;
import static finalsbot.Map.*;
import static finalsbot.Nav.*;
// import static finalsbot.Visualizer.*;

import java.util.Random;

public class RobotPlayer {

    static int cheeseThreshold() {
        if (nearestAllyKingLoc == null) {
            return MAX_CHEESE_CARRIED;
        }
        int dist = rc.getLocation().distanceSquaredTo(nearestAllyKingLoc);

        if (dist <= 32) {
            return MAX_CHEESE_CARRIED_NEXT_TO_KING;
        } else if (dist <= 48) {
            return MAX_CHEESE_CARRIED_NEAR_KING;
        }
        return MAX_CHEESE_CARRIED;
    }

    static boolean shouldCollectCheese() {
        if (rc.getType() == UnitType.RAT_KING) {
            return true;
        }
        return rc.getRawCheese() < cheeseThreshold();
    }

    static void markSpawnsChecked() {
        for (int i = 0; i < 3; i++) {
            if (possibleEnemySpawns[i] != null && isVisited(possibleEnemySpawns[i])) {
                spawnChecked[i] = true;
            }
        }
    }

    static void senseNearbyTiles() throws GameActionException {
        sensedTiles = rc.senseNearbyMapInfos();
        numTraps = 0;

        for (MapInfo tile : sensedTiles) {
            setTile(tile);

            if (tile.getCheeseAmount() > 0) {
                lastSeenCheese = rc.getRoundNum();
                MapLocation cheeseLoc = tile.getMapLocation();
                if (shouldCollectCheese() && !tryPickUpCheese(cheeseLoc, tile.getCheeseAmount())
                        && bestCheeseDist > myLoc.distanceSquaredTo(cheeseLoc) && tile.getCheeseAmount() > 5) {
                    bestCheeseDist = myLoc.distanceSquaredTo(cheeseLoc);
                    visibleCheeseLoc = cheeseLoc;
                }
            }

            RobotInfo flyingRobot = tile.flyingRobot();

            if (flyingRobot != null) {
                MapLocation flyingRobotLoc = flyingRobot.getLocation();
                Direction flyingRobotDir = flyingRobot.getDirection();

                MapLocation nextTile = flyingRobotLoc.add(flyingRobotDir);
                if (rc.onTheMap(nextTile)) {
                    flyingRatDangerSpots.add(nextTile);

                    MapLocation secondTile = nextTile.add(flyingRobotDir);

                    if (rc.onTheMap(secondTile)) {
                        flyingRatDangerSpots.add(secondTile);
                    }
                }
            }
        }

        markSpawnsChecked();
    }

    static void senseNearbyAllies() throws GameActionException {
        sensedAllies = rc.senseNearbyRobots(-1, myTeam);
        if (rc.getType() == UnitType.BABY_RAT) {
            for (RobotInfo ally : sensedAllies) {
                if (ally.getType() == UnitType.RAT_KING) {
                    squeakToKing();
                    int myCheese = rc.getRawCheese();
                    if (myCheese > 0) {
                        if (rc.canTransferCheese(ally.getLocation(), myCheese)) {
                            rc.transferCheese(ally.getLocation(), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(-1, -1), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(-1, -1), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(0, -1), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(0, -1), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(1, -1), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(1, -1), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(1, 0), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(1, 0), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(1, 1), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(1, 1), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(0, 1), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(0, 1), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(-1, 1), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(-1, 1), myCheese);
                        } else if (rc.canTransferCheese(ally.getLocation().translate(-1, 0), myCheese)) {
                            rc.transferCheese(ally.getLocation().translate(-1, 0), myCheese);
                        }
                    }
                    visibleAllyKingLoc = ally.getLocation();
                }
            }
        }
    }

    static void senseNearbyCats() throws GameActionException {
        sensedCats = rc.senseNearbyRobots(rc.getType() == UnitType.BABY_RAT ? 8 : -1, Team.NEUTRAL);
        if (sensedCats.length > 0) {
            catLastSeenLoc = findClosestCat();
            catLastSeenRound = rc.getRoundNum();
        }
    }

    static void senseNearbyEnemies() throws GameActionException {
        sensedEnemies = rc.senseNearbyRobots(-1, enemyTeam);
        if (rc.getType() == UnitType.RAT_KING) {
            sensedAdjacentEnemies = rc.senseNearbyRobots(8, enemyTeam);
        } else {
            sensedAdjacentEnemies = rc.senseNearbyRobots(2, enemyTeam);
        }

        for (RobotInfo enemy : sensedEnemies) {
            if (enemy.getType() == UnitType.RAT_KING && rc.getType() != UnitType.RAT_KING) {
                enemyKingLoc = enemy.getLocation();
                enemyKingLastSeen = rc.getRoundNum();
                squeakEnemyKingLocation();
            }
        }
    }

    static void senseNearby() throws GameActionException {
        senseNearbyTiles();
        senseNearbyAllies();
        senseNearbyEnemies();
        senseNearbyCats();
    }

    static RobotInfo findLowestHPTarget() throws GameActionException {
        RobotInfo bestTarget = null;
        int lowestHP = Integer.MAX_VALUE;

        for (RobotInfo robot : sensedAdjacentEnemies) {
            if (robot.getHealth() < lowestHP) {
                lowestHP = robot.getHealth();
                bestTarget = robot;
            }
        }

        return bestTarget;
    }

    static RobotInfo findBestTarget() throws GameActionException {
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo robot : sensedAdjacentEnemies) {
            int score = 0;
            MapLocation robotLoc = robot.getLocation();

            // if (robot.getType() == UnitType.RAT_KING) {
            // score += 10000;
            // }

            // RobotInfo carryingRat = rc.getCarrying();
            // if (carryingRat != null && carryingRat.getTeam() == myTeam) {
            // score += 1200;
            // }

            score -= robot.getHealth();

            if (nearestAllyKingLoc != null) {
                if (isLocationAdjacentToKing(robotLoc, nearestAllyKingLoc)) {
                    score += 1000;
                } else if (robotLoc.distanceSquaredTo(nearestAllyKingLoc) <= 8) {
                    score += 500;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestTarget = robot;
            }
        }

        return bestTarget;
    }

    static RobotInfo kingFindBestTarget() throws GameActionException {
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo robot : sensedAdjacentEnemies) {
            int score = 0;

            score -= robot.getHealth();

            RobotInfo carryingRat = rc.getCarrying();
            if (carryingRat != null && carryingRat.getTeam() == myTeam) {
                score += 1200;
            }

            if (score > bestScore) {
                bestScore = score;
                bestTarget = robot;
            }
        }

        if (bestTarget == null) {
            int lowestHP = UnitType.CAT.health + 1;
            for (RobotInfo cat : sensedCats) {
                if (cat.getHealth() < lowestHP) {
                    lowestHP = cat.getHealth();
                    bestTarget = cat;
                }
            }
        }

        return bestTarget;
    }

    static RobotInfo findBestRatnappableTarget() throws GameActionException {
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo enemy : sensedAdjacentEnemies) {
            if (enemy.getType() != UnitType.BABY_RAT || !rc.canCarryRat(enemy.getLocation())) {
                continue;
            }

            int score = 0;
            score += enemy.getHealth(); // Prefer higher HP targets

            RobotInfo carryingRat = rc.getCarrying();
            if (carryingRat != null && carryingRat.getTeam() == myTeam) {
                score += 1000;
            }

            if (nearestAllyKingLoc != null) {
                int distToKing = enemy.getLocation().distanceSquaredTo(nearestAllyKingLoc);
                if (distToKing <= 20) {
                    score += 500; // Prioritize enemies near our king
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestTarget = enemy;
            }
        }
        return bestTarget;
    }

    static void tryRatnap() throws GameActionException {
        RobotInfo ratnappable = findBestRatnappableTarget();
        if (ratnappable != null) {
            MapLocation ratLoc = ratnappable.getLocation();
            if (rc.canCarryRat(ratLoc)) {
                rc.carryRat(ratLoc);
            }
        }
    }

    static Direction[] getFrontDirs(Direction dir) {
        return new Direction[] {
                dir,
                dir.rotateLeft(),
                dir.rotateRight(),
        };
    }

    static Direction findBestThrowDirection() throws GameActionException {
        Direction bestDir = null;
        int bestScore = 0;

        for (Direction dir : getFrontDirs(rc.getDirection())) {
            MapLocation throwLoc = myLoc.add(dir);

            int score = 0;
            int iters = 1;

            while (rc.canSenseLocation(throwLoc) || !rc.onTheMap(throwLoc)) {
                if (!rc.onTheMap(throwLoc)) {
                    if (iters > 1) {
                        score = 50 / iters;
                    }
                    break;
                }

                RobotInfo robot = rc.senseRobotAtLocation(throwLoc);
                MapInfo tile = rc.senseMapInfo(throwLoc);

                if (robot != null) {
                    if (iters > 1 && robot.getTeam() == enemyTeam) {
                        score = 200 / iters;
                    }
                    break;
                }
                if (tile != null && (tile.isWall() || tile.isDirt())) {
                    if (iters > 1) {
                        score = 100 / iters;
                    }
                    break;
                }

                throwLoc = throwLoc.add(dir);
                iters++;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestScore > 0) {
            return bestDir;
        }

        return null;
    }

    static void tryThrowRat() throws GameActionException {
        if (!rc.canThrowRat() || isCarryingAlly()) {
            return;
        }

        Direction throwDir = findBestThrowDirection();
        if (throwDir == null) {
            return;
        }

        if (rc.getDirection() != throwDir) {
            if (rc.canTurn(throwDir)) {
                rc.turn(throwDir);
                visionChanged = true;
            } else {
                return;
            }
        }
        safeThrow();
    }

    static void safeThrow() throws GameActionException {
        MapLocation throwLoc = myLoc.add(rc.getDirection());
        while (rc.canSenseLocation(throwLoc)) {
            RobotInfo robot = rc.senseRobotAtLocation(throwLoc);
            if (robot != null && robot.getTeam() == myTeam) {
                return;
            }
            throwLoc = throwLoc.add(rc.getDirection());
        }

        if (rc.canThrowRat()) {
            rc.throwRat();
            justThrew = rc.getCarrying();
            justThrewRound = rc.getRoundNum();
        }
    }

    static void handleTrapPlacement() throws GameActionException {
        if (!rc.isActionReady() || rc.getAllCheese() < CHEESE_CRITICAL_THRESHOLD) {
            return;
        }

        Direction[] frontDirs = getFrontDirs(rc.getDirection());
        MapLocation[] candidateLocs = new MapLocation[3];
        int[] enemyCounts = new int[3];
        int maxNumEnemies = 0;

        for (int i = 0; i < 3; i++) {
            MapLocation trapLoc = myLoc.add(frontDirs[i]);
            if (rc.canPlaceRatTrap(trapLoc)) {
                candidateLocs[i] = trapLoc;
                RobotInfo[] enemiesNextToTrap = rc.senseNearbyRobots(trapLoc, 2, enemyTeam);
                enemyCounts[i] = enemiesNextToTrap.length;
                if (enemyCounts[i] > maxNumEnemies) {
                    maxNumEnemies = enemyCounts[i];
                }
            }
        }

        if (maxNumEnemies < 2 && !(maxNumEnemies >= 1 && rc.getAllCheese() >= CHEESE_EMERGENCY_THRESHOLD)) {
            return;
        }

        MapLocation bestTrapLoc = null;

        for (int i = 0; i < 3; i++) {
            if (enemyCounts[i] == maxNumEnemies) {
                bestTrapLoc = candidateLocs[i];
                break;
            }
        }

        if (bestTrapLoc != null) {
            rc.placeRatTrap(bestTrapLoc);
        }
    }

    static boolean isNearAllyRatKing() throws GameActionException {
        return nearestAllyKingLoc != null
                && myLoc.isWithinDistanceSquared(nearestAllyKingLoc, MIN_SQUARED_DIST_FROM_KING_TO_SPAWN);
    }

    static MapLocation findClosestCat() throws GameActionException {
        int closestDist = Integer.MAX_VALUE;
        MapLocation closestTile = null;

        for (RobotInfo robot : sensedCats) {
            MapLocation candidateTile = getClosestCatTile(robot.getLocation());
            int dist = myLoc.distanceSquaredTo(candidateTile);
            if (dist < closestDist) {
                closestDist = dist;
                closestTile = candidateTile;
            }
        }
        return closestTile;
    }

    static boolean tryPlaceCatTrapInDirection(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER || !rc.isActionReady()) {
            return false;
        }

        MapLocation trapLoc = myLoc.add(dir);
        if (rc.canPlaceCatTrap(trapLoc)) {
            rc.placeCatTrap(trapLoc);
            return true;
        }

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

    static MapLocation findNearbyCheeseLocation() throws GameActionException {
        MapLocation bestCheese = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : sensedTiles) {
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

    static MapLocation findNearestRatKing() throws GameActionException {
        if (visibleAllyKingLoc != null) {
            return visibleAllyKingLoc;
        }

        MapLocation nearestKing = null;
        int nearestDist = Integer.MAX_VALUE;

        for (char i = 0; i < 5; i++) {
            MapLocation kingLoc = currentKingLocs[i];
            if (kingLoc == null) {
                continue;
            }
            int dist = myLoc.distanceSquaredTo(kingLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestKing = kingLoc;
            }
        }

        return nearestKing;
    }

    static int countRatKings() throws GameActionException {
        int count = 0;
        for (char i = 0; i < 5; i++) {
            if (currentKingLocs[i] != null) {
                count++;
            }
        }

        return count;
    }

    static int getCheeseForAttack(RobotInfo target) throws GameActionException {
        if (target.getType() == UnitType.CAT) {
            return 0;
        }

        int cheese = rc.getAllCheese();
        int finishingExtraDamage = target.getHealth() - GameConstants.RAT_BITE_DAMAGE;

        if (finishingExtraDamage <= 0) {
            return 0;
        }

        int deficitExtraDamage = target.getHealth() - GameConstants.RAT_BITE_DAMAGE - rc.getHealth();
        // int advantageExtraDamage = deficitExtraDamage + 1;

        if (cheese > CHEESE_EMERGENCY_THRESHOLD) {
            if (finishingExtraDamage <= 3) {
                return getCheeseForExtraDamage(finishingExtraDamage);
            }
            // if (advantageExtraDamage > 1 && advantageExtraDamage <= 3) {
            // return getCheeseForExtraDamage(advantageExtraDamage);
            // }
            if (deficitExtraDamage > 1 && deficitExtraDamage <= 3) {
                return getCheeseForExtraDamage(deficitExtraDamage);
            }
            // return getCheeseForExtraDamage(2);
            return 0;
        }

        if (cheese > CHEESE_CRITICAL_THRESHOLD && finishingExtraDamage > 0 && finishingExtraDamage <= 2) {
            return getCheeseForExtraDamage(finishingExtraDamage);
        }

        if (cheese > CHEESE_CRITICAL_THRESHOLD && deficitExtraDamage > 0 && deficitExtraDamage <= 2) {
            return getCheeseForExtraDamage(deficitExtraDamage);
        }

        return 0;
    }

    static void tryAttackWithCheese(MapLocation attackLoc, RobotInfo target)
            throws GameActionException {
        if (rc.canAttack(attackLoc)) {
            rc.attack(attackLoc, getCheeseForAttack(target));
        }
    }

    static void tryAttack() throws GameActionException {
        if (!rc.isActionReady()) {
            return;
        }
        RobotInfo target = rc.getType() == UnitType.RAT_KING ? kingFindBestTarget() : findBestTarget();
        if (target == null) {
            return;
        }

        MapLocation targetLoc = target.getLocation();
        boolean isKing = target.getType() == UnitType.RAT_KING;

        if (isKing) {
            targetLoc = getClosestKingTile(targetLoc);
        }

        tryAttackWithCheese(targetLoc, target);
    }

    static MapLocation findMine() throws GameActionException {
        if (mineLocations.isEmpty() || mineLocations.size() <= mineCooldowns.size()) {
            return null;
        }

        int size = mineLocations.size();
        int offset = rc.getID() % size;
        int current = 0;

        Character fallback = null;

        for (char encoded : mineLocations) {
            boolean isValid = !mineCooldowns.contains(encoded);

            MapLocation loc = null;
            if (isValid) {
                loc = decodeLocation(encoded);
                for (MapLocation kingLoc : currentKingLocs) {
                    if (kingLoc != null && loc.distanceSquaredTo(kingLoc) <= 16) {
                        isValid = false;
                        break;
                    }
                }
            }

            if (current >= offset) {
                if (isValid) {
                    return loc;
                }
            } else {
                if (isValid && fallback == null) {
                    fallback = encoded;
                }
            }
            current++;
        }

        if (fallback != null) {
            return decodeLocation(fallback);
        }

        return null;
    }

    static MapLocation findKingMine() throws GameActionException {
        if (mineLocations.isEmpty()) {
            return null;
        }

        MapLocation nearestMine = null;
        int nearestDist = Integer.MAX_VALUE;

        for (char encoded : mineLocations) {
            if (mineCooldowns.contains(encoded)) {
                continue;
            }

            MapLocation mineLoc = decodeLocation(encoded);

            boolean tooCloseToOtherKing = false;
            for (int i = 0; i < 5; i++) {
                if (i == myKingIndex)
                    continue;
                if (currentKingLocs[i] != null && mineLoc.distanceSquaredTo(currentKingLocs[i]) <= 20) {
                    tooCloseToOtherKing = true;
                    break;
                }
            }

            if (tooCloseToOtherKing)
                continue;

            int dist = myLoc.distanceSquaredTo(mineLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestMine = mineLoc;
            }
        }

        return nearestMine;
    }

    static Direction calculateDefensiveDirection(
            RobotInfo[] nearbyEnemies,
            RobotInfo[] additionalEnemies,
            MapLocation loc,
            Direction desired) throws GameActionException {

        if (nearbyEnemies.length == 0 && additionalEnemies.length == 0) {
            return desired;
        }

        int[] validCount = new int[9];
        int threatCount = 0;

        for (RobotInfo enemy : nearbyEnemies) {
            if (enemy.getType() == UnitType.RAT_KING)
                continue;

            threatCount++;

            Direction d = loc.directionTo(enemy.getLocation());
            validCount[d.ordinal()]++;
            validCount[d.rotateLeft().ordinal()]++;
            validCount[d.rotateRight().ordinal()]++;
        }

        for (RobotInfo enemy : additionalEnemies) {
            if (enemy.getType() == UnitType.RAT_KING)
                continue;

            threatCount++;

            Direction d = loc.directionTo(enemy.getLocation());
            validCount[d.ordinal()]++;
            validCount[d.rotateLeft().ordinal()]++;
            validCount[d.rotateRight().ordinal()]++;
        }

        int[] invalidCount = new int[9];
        int minInvalid = Integer.MAX_VALUE;

        for (int i = 0; i < 9; i++) {
            invalidCount[i] = threatCount - validCount[i];
            if (invalidCount[i] < minInvalid) {
                minInvalid = invalidCount[i];
            }
        }

        int desiredOrd = desired.ordinal();

        // If desired direction is already optimal, keep it
        if (invalidCount[desiredOrd] == minInvalid) {
            return desired;
        }

        // Otherwise, find closest direction with minimal invalid count
        int bestOrd = desiredOrd;
        int bestDist = 9;

        for (int i = 0; i < 9; i++) {
            if (invalidCount[i] != minInvalid)
                continue;

            int dist = circularDistance(desiredOrd, i);
            if (dist < bestDist) {
                bestDist = dist;
                bestOrd = i;
            }
        }

        return Direction.values()[bestOrd];
    }

    static int circularDistance(int a, int b) {
        int diff = Math.abs(a - b);
        return Math.min(diff, 8 - diff);
    }

    static int healthDeficit() throws GameActionException {
        return rc.getType().getHealth() - rc.getHealth();
    }

    static void calculateMoveHeuristics() throws GameActionException {
        for (int i = 0; i < 9; i++) {
            Direction dir = ALL_DIRS[i];
            if (dir != Direction.CENTER && !rc.canMove(dir)) {
                impassable[i] = true;
            } else {
                impassable[i] = false;
            }
        }

        for (int i = 0; i < 9; i++) {
            Direction dir = ALL_DIRS[i];
            MapLocation newLoc = myLoc.add(dir);

            boolean isDirtTile = false;
            if (impassable[i]) {
                if (rc.isActionReady()
                        && rc.getGlobalCheese() > CHEESE_EMERGENCY_THRESHOLD
                        && rc.onTheMap(newLoc)
                        && get(newLoc) == Tile.DIRT) {
                    isDirtTile = true;
                } else {
                    scores[i] = HEURISTIC_MIN;
                    actionScores[i] = 0;
                    canRatnapAfterMove[i] = false;
                    continue;
                }
            }

            int score = 0;

            if (isDirtTile) {
                score = HEURISTIC_DIRT_TILE;
            }

            if (dir == Direction.CENTER) {
                score += HEURISTIC_STANDING_STILL;
            } else if (rc.getDirection() != dir && !rc.canTurn()) {
                score += HEURISTIC_STRAFE_PENALTY;
            }

            if (flyingRatDangerSpots.contains(newLoc)) {
                score += HEURISTIC_FLYING_RAT_DANGER;
            }

            if (!kingSOS) {
                if (sensedCats.length > 0) {
                    for (RobotInfo neutral : sensedCats) {
                        MapLocation catLoc = neutral.getLocation();
                        if (isLocationAdjacentToCat(newLoc, catLoc)) {
                            score += catCanSeeLocation(neutral, newLoc)
                                    ? HEURISTIC_CAT_ADJACENT
                                    : HEURISTIC_CAT_ADJACENT / HEURISTIC_CAT_NOT_LOOKING_DIVIDER;
                        } else if (isLocationOneTileFromCat(newLoc, catLoc)) {
                            score += catCanSeeLocation(neutral, newLoc)
                                    ? HEURISTIC_CAT_ONE_TILE
                                    : HEURISTIC_CAT_ONE_TILE / HEURISTIC_CAT_NOT_LOOKING_DIVIDER;
                        }
                    }
                } else if (catLastSeenLoc != null
                        && rc.getRoundNum() - catLastSeenRound <= CAT_FORGET_ROUNDS) {

                    Direction catDir = myLoc.directionTo(catLastSeenLoc);
                    if (dir == catDir) {
                        score += HEURISTIC_CAT_PREVIOUS;
                    } else if (catDir != null &&
                            (dir == catDir.rotateLeft() || dir == catDir.rotateRight())) {
                        score += HEURISTIC_CAT_PREVIOUS / 2;
                    }
                }
            }

            int adj = 0;
            int one = 0;
            int adjHigherHP = 0;
            int oneHigherHP = 0;
            boolean isCardinalEnemy = false;
            boolean canAttackEnemyKing = false;
            boolean canRatnapByPosition = false;
            boolean canRatnapByHP = false;
            boolean canAttack = false;
            boolean canFinish = false;

            for (RobotInfo enemy : sensedEnemies) {
                if (enemy.getType() == UnitType.RAT_KING) {
                    if (isAdjacentToRatKing(enemy.getLocation())) {
                        score += HEURISTIC_ENEMY_KING_ADJACENT;
                        canAttackEnemyKing = true;
                    } else if (isWithinOneTileFromRatKing(enemy.getLocation())) {
                        score += HEURISTIC_ENEMY_KING_ONE_TILE;
                    }
                } else if (enemy.getType() == UnitType.BABY_RAT) {
                    if (newLoc.isAdjacentTo(enemy.getLocation()) && rc.isActionReady()) {
                        if (isRatnappableByPosition(enemy, newLoc)) {
                            canRatnapByPosition = true;
                        } else if (isRatnappableByHP(enemy)) {
                            canRatnapByHP = true;
                        } else {
                            canAttack = true;
                            int wouldInvest = getCheeseForAttack(enemy);
                            if (getExtraDamageFromCheese(wouldInvest) + GameConstants.RAT_BITE_DAMAGE >= enemy
                                    .getHealth()) {
                                canFinish = true;
                            }
                        }
                    }

                    int distSq = newLoc.distanceSquaredTo(enemy.getLocation());
                    boolean justThrewThis = justThrew != null && enemy.getID() == justThrew.getID()
                            && rc.getRoundNum() - justThrewRound <= THRESHOLD_FOR_JUST_THREW;
                    if (distSq <= 2) {
                        if (justThrewThis) {
                            score += HEURISTIC_ENEMY_RAT_ADJACENT_JUST_THREW;
                        } else {
                            adj++;
                            if (enemy.getHealth() > rc.getHealth()) {
                                adjHigherHP++;
                            }
                            if (distSq <= 1) {
                                isCardinalEnemy = true;
                            }
                        }
                    } else if (distSq <= 8) {
                        Direction toTarget = myLoc.directionTo(enemy.getLocation());
                        Direction left = toTarget.rotateLeft();
                        Direction right = toTarget.rotateRight();

                        if (impassable[toTarget.ordinal()] && impassable[left.ordinal()]
                                && impassable[right.ordinal()]) {
                            continue;
                        }

                        if (justThrewThis) {
                            score += HEURISTIC_ENEMY_RAT_ONE_TILE_JUST_THREW;
                        } else {
                            one++;
                            if (enemy.getHealth() > rc.getHealth()) {
                                oneHigherHP++;
                            }
                        }
                    }

                }
            }

            if (!kingSOS) {

                double ratio = allyIDs.size() / (double) sensedEnemies.length;
                double multiplier = 1;
                if (ratio > 2) {
                    multiplier = 1 / ratio;
                }
                score += adj * HEURISTIC_ENEMY_RAT_ADJACENT * multiplier;
                score += one * HEURISTIC_ENEMY_RAT_ONE_TILE * multiplier;
                score += adjHigherHP * HEURISTIC_ENEMY_RAT_ADJACENT_HIGHER_HP * multiplier;
                score += oneHigherHP * HEURISTIC_ENEMY_RAT_ONE_TILE_HIGHER_HP * multiplier;
            } else {
                score -= adj * HEURISTIC_ENEMY_RAT_ADJACENT;
                score -= one * HEURISTIC_ENEMY_RAT_ONE_TILE;
            }

            if (adj > 0 || one > 0) {
                score -= healthDeficit() * HEURISTIC_HEALTH_DEFICIT_MULTIPLIER;
            }

            int actionScore = 0;
            if (rc.isActionReady()) {
                if (canRatnapByPosition) {
                    actionScore = HEURISTIC_RATNAP_POSITION_BONUS;
                } else if (canRatnapByHP) {
                    actionScore = HEURISTIC_RATNAP_HP_BONUS;
                } else if (canAttackEnemyKing) {
                    actionScore = HEURISTIC_ATTACK_KING_BONUS;
                } else if (canFinish) {
                    actionScore = HEURISTIC_FINISHER_ATTACK_BONUS;
                } else if (canAttack) {
                    actionScore = HEURISTIC_READY_ATTACK_BONUS;
                }
                score += actionScore;
            }
            actionScores[i] = actionScore;
            canRatnapAfterMove[i] = canRatnapByPosition || canRatnapByHP;

            if (!isCardinalEnemy) {
                score -= HEURISTIC_IS_ENEMY_RAT_CARDINAL;
            }

            if (destination != null) {
                score -= (int) Math.sqrt(newLoc.distanceSquaredTo(destination)) * HEURISTIC_DESTINATION_MULTIPLIER;
            }

            // if (currentState == State.COLLECT) {
            // score += getAdjacentCheeseAmount(newLoc) *
            // HEURISTIC_ADJACENT_CHEESE_MULTIPLIER;
            // }

            scores[i] = score;
        }
    }

    static Direction getBestMove() throws GameActionException {
        calculateMoveHeuristics();

        int bestIndex = 0;
        int bestScore = scores[0];
        for (int i = 1; i < 9; i++) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestIndex = i;
            }
        }

        return ALL_DIRS[bestIndex];
    }

    static Direction getBestMoveWithoutActionScores() {
        int bestIndex = 0;
        int bestScore = scores[0] - actionScores[0];
        for (int i = 1; i < 9; i++) {
            int adjustedScore = scores[i] - actionScores[i];
            if (adjustedScore > bestScore) {
                bestScore = adjustedScore;
                bestIndex = i;
            }
        }
        return ALL_DIRS[bestIndex];
    }

    static void tryFinish() throws GameActionException {
        if (!rc.isActionReady()) {
            return;
        }
        RobotInfo lowestHPTarget = findLowestHPTarget();
        if (lowestHPTarget != null) {
            MapLocation targetLoc = lowestHPTarget.getLocation();

            int cheeseNeeded = getCheeseForAttack(lowestHPTarget);
            int damageDealt = GameConstants.RAT_BITE_DAMAGE + getExtraDamageFromCheese(cheeseNeeded);
            if (rc.canAttack(targetLoc) && damageDealt >= lowestHPTarget.getHealth()) {
                rc.attack(targetLoc, cheeseNeeded);
            }
        }
    }

    static void doAttack() throws GameActionException {
        if (rc.isActionReady()) {
            // tryFinish();
            tryRatnap();
            tryThrowRat();
            tryAttack();
        }
    }

    static void doCatAttack() throws GameActionException {
        if (!rc.isActionReady() || sensedCats.length == 0 || sensedEnemies.length > 0) {
            return;
        }

        if (possibleEnemyLoc != null && possibleEnemyLoc.isWithinDistanceSquared(myLoc, 8)) {
            return;
        }

        RobotInfo bestCat = null;
        MapLocation bestAttackLoc = null;
        int minHp = Integer.MAX_VALUE;

        for (Direction dir : getFrontDirs(rc.getDirection())) {
            MapLocation attackLoc = myLoc.add(dir);
            if (rc.canSenseLocation(attackLoc)) {
                RobotInfo cat = rc.senseRobotAtLocation(attackLoc);
                if (cat != null && cat.getTeam() == Team.NEUTRAL && cat.getHealth() < minHp) {
                    minHp = cat.getHealth();
                    bestCat = cat;
                    bestAttackLoc = attackLoc;
                }
            }
        }

        if (bestCat != null) {
            tryAttackWithCheese(bestAttackLoc, bestCat);
            return;
        }

        boolean allowCatAttack = kingSOS || rc.getRoundNum() > AGGRESSION_ROUND;

        if (allowCatAttack && rc.isCooperation() && rc.getAllCheese() >= 1.5 * CHEESE_EMERGENCY_THRESHOLD) {
            MapLocation bestTrapLoc = null;
            int bestTrapCount = 0;

            for (Direction dir : getFrontDirs(rc.getDirection())) {
                MapLocation trapLoc = myLoc.add(dir);
                if (rc.canPlaceCatTrap(trapLoc)) {
                    RobotInfo[] adjacentCats = rc.senseNearbyRobots(trapLoc, 2, Team.NEUTRAL);
                    if (adjacentCats.length > bestTrapCount) {
                        bestTrapCount = adjacentCats.length;
                        bestTrapLoc = trapLoc;
                    }
                }
            }

            if (bestTrapLoc != null) {
                rc.placeCatTrap(bestTrapLoc);
                return;
            }
        }
    }

    /**
     * Cheese Courier Logic: If we're in COLLECT with 0 cheese and see an ally
     * with 100 cheese, pick them up and return to king.
     * Returns true if courier action was initiated.
     */
    static boolean tryCheeseCourier() throws GameActionException {
        if (!rc.isActionReady())
            return false;
        if (currentState != State.COLLECT)
            return false;
        if (rc.getRawCheese() > 0)
            return false;
        if (sensedEnemies.length > 0 || sensedCats.length > 0)
            return false;
        if (nearestAllyKingLoc != null && nearestAllyKingLoc.isWithinDistanceSquared(myLoc, 32))
            return false;

        RobotInfo wealthyAlly = findWealthyAlly();
        if (wealthyAlly == null)
            return false;

        if (!rc.canCarryRat(wealthyAlly.getLocation())) {
            return false;
        }
        rc.carryRat(wealthyAlly.getLocation());

        currentState = State.RETURN;
        clearDestination();
        if (nearestAllyKingLoc != null) {
            setDestination(nearestAllyKingLoc, false);
        }

        return true;
    }

    static boolean tryDropAllyNearKing() throws GameActionException {
        if (rc.getCarrying() == null)
            return false;
        if (nearestAllyKingLoc == null)
            return false;

        if (myLoc.distanceSquaredTo(nearestAllyKingLoc) > 18) {
            return false;
        }

        Direction toKing = myLoc.directionTo(nearestAllyKingLoc);

        if (rc.canDropRat(toKing)) {
            rc.dropRat(toKing);
            return true;
        }

        Direction left = toKing.rotateLeft();

        if (rc.canDropRat(left)) {
            rc.dropRat(left);
            return true;
        }

        Direction right = toKing.rotateRight();

        if (rc.canDropRat(right)) {
            rc.dropRat(right);
            return true;
        }

        left = left.rotateLeft();

        if (rc.canDropRat(left)) {
            rc.dropRat(left);
            return true;
        }

        right = right.rotateRight();

        if (rc.canDropRat(right)) {
            rc.dropRat(right);
            return true;
        }

        left = left.rotateLeft();

        if (rc.canDropRat(left)) {
            rc.dropRat(left);
            return true;
        }

        right = right.rotateRight();

        if (rc.canDropRat(right)) {
            rc.dropRat(right);
            return true;
        }

        left = left.rotateLeft();

        if (rc.canDropRat(left)) {
            rc.dropRat(left);
            return true;
        }

        return false;
    }

    static Direction chooseRandomNearbyTurn() throws GameActionException {
        Direction currentDir = rc.getDirection();
        int roll = rng.nextInt(2);
        Direction newDir;

        switch (roll) {
            case 0:
                newDir = currentDir.rotateLeft();
                break;
            case 1:
                newDir = currentDir.rotateRight();
                break;
            default:
                newDir = currentDir;
                break;
        }

        return newDir;
    }

    static void evadeCat() throws GameActionException {
        if (sensedCats.length != 0) {
            if (catLastSeenLoc != null && chosenMine != null
                    && catLastSeenLoc.isWithinDistanceSquared(chosenMine, 16)) {
                mineCooldowns.add(chosenMine);
                chosenMine = null;
                arrivedAtChosenMine = false;
            }
        }

        if (catLastSeenLoc != null && rc.getRoundNum() - catLastSeenRound <= KING_CAT_FORGET_ROUNDS) {
            Direction toCat = myLoc.directionTo(catLastSeenLoc);
            if (rc.getRoundNum() == catLastSeenRound && rc.isActionReady()) {
                if (rc.isCooperation()) {
                    kingTryPlaceCatTrapInDirection(toCat);
                } else if (lastSpawnedRound + 2 <= rc.getRoundNum()
                        && rc.getAllCheese() - rc.getCurrentRatCost() >= CHEESE_EMERGENCY_THRESHOLD) {
                    if (trySpawnInDir(toCat)) {
                        lastSpawnedRound = rc.getRoundNum();
                    }
                } else if (rng.nextInt(6) == 0) {
                    kingTryPlaceDirtInDirection(toCat);
                }
            }

            if (!rc.isMovementReady()) {
                return;
            }

            boolean useSmartEvade = true;
            RobotInfo visibleCat = null;

            if (sensedCats.length == 1 && rc.getRoundNum() == catLastSeenRound) {
                visibleCat = sensedCats[0];

                if (visibleCat.getLocation().equals(catLastSeenLoc)) {
                    Direction catDir = visibleCat.getDirection();
                    Direction dirToCat = myLoc.directionTo(catLastSeenLoc);

                    if (dirToCat == catDir || dirToCat == catDir.rotateLeft() || dirToCat == catDir.rotateRight()) {
                        useSmartEvade = false;
                    }
                }
            } else {
                useSmartEvade = false;
            }

            if (useSmartEvade) {
                if (rc.isMovementReady()) {
                    Direction bestDir = null;
                    int maxRayDist = -1;
                    int currentDistSq = myLoc.distanceSquaredTo(catLastSeenLoc);

                    Direction catDir = visibleCat.getDirection();
                    int cdx = catDir.getDeltaX();
                    int cdy = catDir.getDeltaY();

                    for (Direction d : Direction.values()) {
                        if (d == Direction.CENTER) {
                            continue;
                        }

                        if (rc.canMove(d)) {
                            MapLocation newLoc = myLoc.add(d);
                            int newDistSq = newLoc.distanceSquaredTo(catLastSeenLoc);

                            if (newDistSq >= currentDistSq) {
                                int rayMetric = Math
                                        .abs(cdx * (newLoc.y - catLastSeenLoc.y) - cdy * (newLoc.x - catLastSeenLoc.x));

                                if (rayMetric > maxRayDist) {
                                    maxRayDist = rayMetric;
                                    bestDir = d;
                                } else if (rayMetric == maxRayDist) {
                                    if (bestDir == null
                                            || newDistSq > myLoc.add(bestDir).distanceSquaredTo(catLastSeenLoc)) {
                                        bestDir = d;
                                    }
                                }
                            }
                        }
                    }

                    if (bestDir != null) {
                        rc.move(bestDir);
                    }
                }

            } else {
                Direction awayFromCat = catLastSeenLoc.directionTo(myLoc);

                if (rc.isMovementReady()) {
                    if (rc.canMove(awayFromCat)) {
                        rc.move(awayFromCat);
                    } else if (rc.canMove(awayFromCat.rotateLeft())) {
                        rc.move(awayFromCat.rotateLeft());
                    } else if (rc.canMove(awayFromCat.rotateRight())) {
                        rc.move(awayFromCat.rotateRight());
                    } else if (rc.canMove(awayFromCat.rotateLeft().rotateLeft())) {
                        rc.move(awayFromCat.rotateLeft().rotateLeft());
                    } else if (rc.canMove(awayFromCat.rotateRight().rotateRight())) {
                        rc.move(awayFromCat.rotateRight().rotateRight());
                    } else {
                        // Only try to dig in greedy mode
                        if (rc.getAllCheese() - GameConstants.DIG_DIRT_CHEESE_COST >= CHEESE_EMERGENCY_THRESHOLD) {
                            kingTryDigToward(awayFromCat);
                        }
                    }
                }
            }

            myLoc = rc.getLocation();
        }
    }

    static void evadeRats() throws GameActionException {
        RobotInfo nearestEnemyRat = null;
        int nearestEnemyRatDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : sensedEnemies) {
            Direction toEnemy = myLoc.directionTo(enemy.getLocation());
            int dist = myLoc.distanceSquaredTo(enemy.getLocation());

            if (dist <= 13) {
                if (rc.getAllCheese() - rc.getCurrentRatCost() >= CHEESE_CRITICAL_THRESHOLD && trySpawnInDir(toEnemy)) {
                    lastSpawnedRound = rc.getRoundNum();
                }

                if (rc.getAllCheese() >= CHEESE_CRITICAL_THRESHOLD) {
                    kingTryPlaceRatTrapInDirection(toEnemy);
                }
            }
            if (dist < nearestEnemyRatDist) {
                nearestEnemyRatDist = dist;
                nearestEnemyRat = enemy;
            }
        }

        if (nearestEnemyRat != null) {
            Direction toEnemy = myLoc.directionTo(nearestEnemyRat.getLocation());
            if (rc.canTurn(toEnemy)) {
                rc.turn(toEnemy);
                visionChanged = true;
            }
            if (rc.getAllCheese() - rc.getCurrentRatCost() >= CHEESE_CRITICAL_THRESHOLD && trySpawnInDir(toEnemy)) {
                lastSpawnedRound = rc.getRoundNum();
            }
            if (nearestEnemyRatDist <= 18) {
                if (rc.getAllCheese() >= CHEESE_CRITICAL_THRESHOLD) {
                    kingTryPlaceRatTrapInDirection(toEnemy);
                }
                if (rc.isMovementReady()) {
                    Direction awayFromEnemy = nearestEnemyRat.getLocation().directionTo(myLoc);
                    if (rc.canMove(awayFromEnemy)) {
                        rc.move(awayFromEnemy);
                    } else if (rc.canMove(awayFromEnemy.rotateLeft())) {
                        rc.move(awayFromEnemy.rotateLeft());
                    } else if (rc.canMove(awayFromEnemy.rotateRight())) {
                        rc.move(awayFromEnemy.rotateRight());
                    }
                }
                myLoc = rc.getLocation();
            }
        }
    }

    static boolean shouldSpawnRats() throws GameActionException {
        if (!rc.isActionReady() || rc.getAllCheese() - rc.getCurrentRatCost() < 0) {
            return false;
        }
        int remaining = rc.getAllCheese() - rc.getCurrentRatCost();

        if (remaining >= CRAZY_AMOUNTS_OF_CHEESE_THRESHOLD
                || remaining >= CHEESE_EMERGENCY_THRESHOLD && rc.getRoundNum() >= 1950) {
            return true;
        }

        int estimatedAliveRats = estimateAliveRats();

        int threshold = MAX_ALIVE_RATS;

        if (remaining > CRAZY_AMOUNTS_OF_CHEESE_THRESHOLD) {
            threshold = MAX_ALIVE_RATS_CRAZY_AMOUNTS_OF_CHEESE;
        } else if (remaining > LOTS_OF_CHEESE_THRESHOLD) {
            threshold = MAX_ALIVE_RATS_LOTS_OF_CHEESE;
        } else if (remaining > CHEESE_SURPLUS_THRESHOLD) {
            threshold = MAX_ALIVE_RATS_CHEESE_SURPLUS;
        } else if (remaining < CHEESE_EMERGENCY_THRESHOLD) {
            if (chosenMine != null && myLoc.isWithinDistanceSquared(chosenMine, 20)) {
                threshold = MIN_ALIVE_RATS_WHEN_ON_CHEESE_MINE;
            } else {
                threshold = MIN_ALIVE_RATS;
            }
        }

        if (estimatedAliveRats < threshold) {
            return true;
        }
        return false;
    }

    static void spawnRats() throws GameActionException {
        if (shouldSpawnRats()) {
            if (myKingIndex == 0) {
                if (trySpawnInDir(myLoc.directionTo(centerLocation))) {
                    lastSpawnedRound = rc.getRoundNum();
                }
            } else {
                if (trySpawnInDir(DIRS[rng.nextInt(8)])) {
                    lastSpawnedRound = rc.getRoundNum();
                }
            }
        }
    }

    static boolean isWallOrBoundary(MapLocation loc) {
        if (loc.x < 0 || loc.x >= mapWidth || loc.y < 0 || loc.y >= mapHeight) {
            return true;
        }
        return get(loc) == Tile.WALL || get(loc) == Tile.DIRT;
    }

    static State decideState() throws GameActionException {
        if (ratKingCenter != null) {
            return State.BUILD_RAT_KING;
        }
        if (currentState == State.NONE) {
            if (rc.getGlobalCheese() < CHEESE_EMERGENCY_THRESHOLD) {
                return State.COLLECT;
            }
            if (rc.getRoundNum() % 2 == 0) {
                return State.COLLECT;
            }
            return State.FIND_KING;

        }

        if (rc.getCarrying() != null && rc.getCarrying().getTeam() == myTeam) {
            return State.RETURN;
        }

        if (nearestAllyKingLoc != null && myLoc.distanceSquaredTo(nearestAllyKingLoc) <= 16
                && kingSOS) {
            return State.RETURN;
        }

        int cheese = rc.getRawCheese();

        if (haveNewSymmetryInfo()) {
            return State.RETURN;
        }

        if (cheese > 0 && currentState == State.RETURN) {
            return State.RETURN;
        }

        if (rc.getRawCheese() >= MAX_CHEESE_TO_RETURN) {
            if (lastSeenCheese < rc.getRoundNum() - 1) {
                return State.RETURN;
            } else if (rc.getRawCheese() >= MAX_CHEESE_TO_RETURN * 2) {
                return State.RETURN;
            }
        }

        if (cheese > 0 && rc.getGlobalCheese() <= CHEESE_CRITICAL_THRESHOLD) {
            return State.RETURN;
        }

        if (enemyKingLoc != null) {
            return State.FIND_KING;
        }

        if (visibleCheeseLoc != null && shouldCollectCheese()) {
            return State.COLLECT;
        }

        if (rc.getID() % 2 == 0 || rc.getGlobalCheese() < CHEESE_EMERGENCY_THRESHOLD) {
            if (findMine() != null && shouldCollectCheese()) {
                return State.COLLECT;
            }
        }

        if (currentState == State.FIND_KING) {
            if (symmetry != Symmetry.UNKNOWN) {
                int idx = symmetry.ordinal() - 1;
                if (!spawnChecked[idx])
                    return State.FIND_KING;
            } else {
                if (!spawnChecked[0] || !spawnChecked[1] || !spawnChecked[2]) {
                    return State.FIND_KING;
                }
            }
        }

        return State.EXPLORE;
    }

    static void pickSpawnTarget() throws GameActionException {
        if (symmetry != Symmetry.UNKNOWN) {
            int confirmedIndex = symmetry.ordinal() - 1;

            if (spawnChecked[confirmedIndex]) {
                setDestination(getUnexploredLocation(), false);
            } else {
                setDestination(possibleEnemySpawns[confirmedIndex], false);
            }
            return;
        }

        boolean allSpawnsChecked = spawnChecked[0] && spawnChecked[1] && spawnChecked[2];
        if (allSpawnsChecked) {
            setDestination(getUnexploredLocation(), false);
            return;
        }

        if (currentTargetIndex < 0) {
            currentTargetIndex = rng.nextInt(3);
        }

        if (spawnChecked[currentTargetIndex]) {
            for (int i = 1; i <= 3; i++) {
                int idx = (currentTargetIndex + i) % 3;
                if (!spawnChecked[idx]) {
                    currentTargetIndex = idx;
                    break;
                }
            }
        }

        setDestination(possibleEnemySpawns[currentTargetIndex], false);
    }

    static void decideDestination() throws GameActionException {
        if (destination != null) {
            if (myLoc.equals(destination)) {
                clearDestination();
            } else if (!destinationExact && rc.canSenseLocation(destination)) {
                clearDestination();
            } else {
                return;
            }
        }

        switch (currentState) {
            case FIND_KING:
                if (enemyKingLoc != null) {
                    setDestination(enemyKingLoc, false);
                } else {
                    pickSpawnTarget();
                }
                break;

            case COLLECT:
                if (visibleCheeseLoc != null) {
                    setDestination(visibleCheeseLoc, true);
                } else {
                    if (chosenMine == null) {
                        chosenMine = findMine();
                        arrivedAtChosenMine = false;
                    }
                    if (chosenMine != null) {
                        setDestination(chosenMine, false);
                    } else {
                        setDestination(getUnexploredLocation(), false);
                    }
                }
                break;

            case RETURN:
                chosenMine = null;
                arrivedAtChosenMine = false;
                mineCooldowns.clear();
                if (nearestAllyKingLoc != null) {
                    setDestination(nearestAllyKingLoc, false);
                }
                break;
            case BUILD_RAT_KING:
                if (ratKingCenter != null) {
                    if (!rc.canSenseLocation(ratKingCenter) && !myLoc.isAdjacentTo(ratKingCenter)) {
                        setDestination(ratKingCenter, false);
                        return;
                    } else if (myLoc.equals(ratKingCenter)) {
                        setDestination(ratKingCenter, true);
                        if (reachedRatKingCenter < 0) {
                            reachedRatKingCenter = rc.getRoundNum();
                        }
                        return;
                    } else if (rc.canSenseLocation(ratKingCenter) && rc.senseRobotAtLocation(ratKingCenter) == null) {
                        setDestination(ratKingCenter, true);
                        return;
                    } else if (myLoc.isAdjacentTo(ratKingCenter)) {
                        setDestination(myLoc, true);
                        if (reachedRatKingCenter < 0) {
                            reachedRatKingCenter = rc.getRoundNum();
                        }
                        return;
                    } else {
                        MapLocation bestAdjacentTile = findEmptyAdjacentTile(ratKingCenter);
                        if (bestAdjacentTile != null) {
                            setDestination(bestAdjacentTile, true);
                        } else {
                            setDestination(ratKingCenter, false);
                        }
                        return;
                    }
                }
                break;

            case EXPLORE:
            default:
                setDestination(getUnexploredLocation(), false);
                break;
        }
    }

    static MapLocation findEmptyAdjacentTile(MapLocation center) throws GameActionException {
        MapLocation bestTile = null;
        int bestDist = Integer.MAX_VALUE;

        for (Direction dir : DIRS) {
            MapLocation adjLoc = center.add(dir);
            if (!rc.onTheMap(adjLoc))
                continue;

            if (rc.canSenseLocation(adjLoc)) {
                MapInfo info = rc.senseMapInfo(adjLoc);
                if (info.isWall() || info.isDirt())
                    continue;

                RobotInfo robot = rc.senseRobotAtLocation(adjLoc);
                if (robot == null) {
                    int dist = myLoc.distanceSquaredTo(adjLoc);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestTile = adjLoc;
                    }
                }
            } else {
                int dist = myLoc.distanceSquaredTo(adjLoc);
                if (bestTile == null || dist < bestDist) {
                    bestDist = dist;
                    bestTile = adjLoc;
                }
            }
        }

        return bestTile;
    }

    static void manageKingLifecycle() throws GameActionException {
        Comms.toggleAliveBit(myKingIndex);
        boolean inDanger = sensedEnemies.length > 0;
        for (RobotInfo cat : sensedCats) {
            MapLocation closest = getClosestCatTile(cat.getLocation());
            if (myLoc.isWithinDistanceSquared(closest, 16)) {
                inDanger = true;
                break;
            }
        }
        Comms.setSOSBit(myKingIndex, inDanger);

        int aliveBits = Comms.readAliveBits();

        for (int i = 0; i < 5; i++) {
            if (i == myKingIndex)
                continue;

            MapLocation loc = currentKingLocs[i];
            if (loc != null) {
                boolean currentBit = (aliveBits & (1 << i)) != 0;

                if (!kingIsAlive[i]) {
                    kingIsAlive[i] = true;
                    prevAliveBitState[i] = currentBit;
                } else {
                    if (currentBit == prevAliveBitState[i]) {
                        rc.writeSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + i, 0);
                        Comms.setSOSBit(i, false);
                        kingIsAlive[i] = false;
                    } else {
                        prevAliveBitState[i] = currentBit;
                    }
                }
            } else {
                kingIsAlive[i] = false;
            }
        }
    }

    static void checkSOSBits() throws GameActionException {
        if (nearestAllyKingLoc == null || myLoc.distanceSquaredTo(nearestAllyKingLoc) > 16) {
            return;
        }
        int sosBits = Comms.readSOSBits();
        if (sosBits != 0) {
            for (int i = 0; i < 5; i++) {
                if ((sosBits & (1 << i)) != 0) {
                    MapLocation loc = currentKingLocs[i];
                    if (loc != null && myLoc.distanceSquaredTo(loc) <= 16) {
                        kingSOS = true;
                        kingSOSRound = rc.getRoundNum();
                    }
                }
            }
        }
    }

    static void initRobotPlayer(RobotController rc) {
        Globals.rc = rc;
        rng = new Random(rc.getID());
        initMap(rc.getMapWidth(), rc.getMapHeight());
        initLossyEncoding();
        myLoc = rc.getLocation();
        myTeam = rc.getTeam();
        enemyTeam = myTeam.opponent();
        centerLocation = new MapLocation(mapWidth / 2, mapHeight / 2);
    }

    static void kingResetTurn() throws GameActionException {
        Direction toCenter = myLoc.directionTo(centerLocation);
        if (rc.canTurn(toCenter)) {
            rc.turn(toCenter);
            visionChanged = true;
        }
    }

    static void initRatKing() throws GameActionException {
        kingStartLoc = myLoc;
        writeStartingKingPosition();
        kingResetTurn();
        prevAliveBitState = new boolean[5];
        kingIsAlive = new boolean[5];
    }

    static void initBabyRat() throws GameActionException {
        MapLocation kingStartLoc = readStartingKingPosition();
        possibleEnemySpawns[0] = getSymmetricLocation(kingStartLoc, Symmetry.FLIP_X);
        possibleEnemySpawns[1] = getSymmetricLocation(kingStartLoc, Symmetry.FLIP_Y);
        possibleEnemySpawns[2] = getSymmetricLocation(kingStartLoc, Symmetry.ROTATE);
        startRotateLeft = rc.getID() % 2 == 0;
    }

    static void runRatKing() throws GameActionException {
        if (prevRound != -1 && rc.getRoundNum() - prevRound != 1) {
            System.out.println("BYTECODE EXCEEDED LAST TURN");
        }

        prevRound = rc.getRoundNum();

        myLoc = rc.getLocation();

        numRatKings = countRatKings();
        if (numRatKings > prevNumRatKings || !becomeKingRequiredConditions()) {
            clearFormRatKingLoc();
        } else {
            MapLocation formLoc = readFormRatKingLoc();
            if (formLoc != null && formLoc.isWithinDistanceSquared(myLoc, MIN_SQUARED_DIST_FROM_KING_TO_SPAWN)) {
                clearFormRatKingLoc();
            }
        }
        prevNumRatKings = numRatKings;

        syncSharedArray();

        if (rc.getRoundNum() > 1) {
            senseNearby();
        }

        updateSymmetry();
        manageKingLifecycle();
        listenForSqueaks();

        evadeCat();

        if (rc.getAllCheese() < CHEESE_CRITICAL_THRESHOLD) {
            tryAttack();
        }

        evadeRats();

        if (rc.isActionReady() && checkKingTrapped(myLoc)) {
            kingTryDigToward(DIRS[rng.nextInt(DIRS.length)]);
        }

        if (rc.isMovementReady() && (catLastSeenLoc == null
                || rc.getRoundNum() - catLastSeenRound > KING_CAT_FORGET_ROUNDS)) {

            if (visibleCheeseLoc != null) {
                setDestination(visibleCheeseLoc, true);
            } else {
                chosenMine = findKingMine();
                if (chosenMine != null) {
                    setDestination(chosenMine, true);
                }
            }
            Nav.goTo(destination);
        }

        if (visionChanged && Clock.getBytecodeNum() >= 4000) {
            sensedAdjacentEnemies = rc.senseNearbyRobots(myLoc, 8, enemyTeam);
        }

        spawnRats();
        tryAttack();

        writeCurrentKingPosition();

        visibleCheeseLoc = null;
        bestCheeseDist = Integer.MAX_VALUE;

        checkRequestNewRatKing();

        if (Clock.getBytecodeNum() >= 6000 && rc.getRoundNum() > 2) {
            senseNearbyTiles();
        }
    }

    static void checkRequestNewRatKing() throws GameActionException {
        if (!becomeKingRequiredConditions()) {
            writeFormRatKingLoc(null);
            return;
        }

        if (readFormRatKingLoc() != null) {
            return;
        }
        if (rc.getAllCheese() - GameConstants.RAT_KING_UPGRADE_CHEESE_COST < summonKingThreshold(numRatKings)) {
            return;
        }
        if (symmetry != Symmetry.UNKNOWN ? sharedArrayMineCount < numRatKings
                : sharedArrayMineCount < (numRatKings * 2)) {
            return;
        }

        MapLocation formLoc = findFormRatKingLocation();
        if (formLoc != null) {
            writeFormRatKingLoc(formLoc);
        }
    }

    static MapLocation findFormRatKingLocation() throws GameActionException {
        for (int i = 0; i < sharedArrayMineCount; i++) {
            int encodedMine = rc.readSharedArray(SHARED_MINES_START_INDEX + i);
            if (encodedMine == 0) {
                continue;
            }

            MapLocation mineLoc = decodeLocation(encodedMine);

            boolean tooClose = false;
            for (int k = 0; k < 5; k++) {
                MapLocation kingLoc = currentKingLocs[k];
                if (kingLoc != null && kingLoc.isWithinDistanceSquared(mineLoc, MIN_SQUARED_DIST_FROM_KING_TO_SPAWN)) {
                    tooClose = true;
                    break;
                }
            }

            if (!tooClose) {
                return mineLoc;
            }
        }
        return null;
    }

    static int becomeKingThreshold(int numKings) throws GameActionException {
        if (numKings > 2
                && rc.getRoundNum() >= GameConstants.RAT_KING_CUTOFF_ROUND - ROUNDS_BEFORE_CUTOFF_PANIC_SPAWN) {
            return (int) ((numKings + 1.25) * CHEESE_EMERGENCY_THRESHOLD);
        }
        return (int) ((numKings + 1.5) * CHEESE_EMERGENCY_THRESHOLD);
    }

    static int summonKingThreshold(int numKings) throws GameActionException {
        if (numKings > 2
                && rc.getRoundNum() >= GameConstants.RAT_KING_CUTOFF_ROUND - ROUNDS_BEFORE_CUTOFF_PANIC_SPAWN) {
            return (int) ((numKings + 1.5) * CHEESE_EMERGENCY_THRESHOLD);
        }
        return (numKings + 2) * CHEESE_EMERGENCY_THRESHOLD;
    }

    static boolean isUnratnappable(
            MapLocation loc,
            Direction facing,
            RobotInfo[] nearbyEnemies) {
        for (RobotInfo enemy : nearbyEnemies) {
            if (enemy.getType() == UnitType.RAT_KING)
                continue;

            if (loc.distanceSquaredTo(enemy.getLocation()) > 8)
                continue;

            Direction enemyDir = loc.directionTo(enemy.getLocation());

            boolean facingEnemy = facing == enemyDir ||
                    facing == enemyDir.rotateLeft() ||
                    facing == enemyDir.rotateRight();

            if (enemy.getHealth() >= rc.getHealth() || !facingEnemy) {
                return false;
            }
        }
        return true;
    }

    static void tryLastResortThrow() throws GameActionException {
        if (!rc.canTurn() || !rc.canThrowRat() || nearestAllyKingLoc == null) {
            return;
        }
        Direction awayFromKing = nearestAllyKingLoc.directionTo(myLoc);
        boolean foundEmpty = false;
        if (rc.canMove(awayFromKing)) {
            rc.turn(nearestAllyKingLoc.directionTo(myLoc));
            visionChanged = true;
            foundEmpty = true;

        } else if (rc.canMove(awayFromKing.rotateLeft())) {
            rc.turn(awayFromKing.rotateLeft());
            visionChanged = true;
            foundEmpty = true;
        } else if (rc.canMove(awayFromKing.rotateRight())) {
            rc.turn(awayFromKing.rotateRight());
            visionChanged = true;
            foundEmpty = true;
        }
        if (foundEmpty) {
            Direction throwDir = rc.getDirection();
            MapLocation twoFront = myLoc.add(throwDir).add(throwDir);
            if (!rc.onTheMap(twoFront)) {
                rc.throwRat();
                justThrew = rc.getCarrying();
                justThrewRound = rc.getRoundNum();
                return;
            }
            MapLocation threeFront = myLoc.add(throwDir).add(throwDir).add(throwDir);
            if (!rc.onTheMap(threeFront)) {
                rc.throwRat();
                justThrew = rc.getCarrying();
                justThrewRound = rc.getRoundNum();
                return;
            }
            if (rc.canSenseLocation(twoFront)) {
                RobotInfo second = rc.senseRobotAtLocation(twoFront);
                if (second == null || second.getTeam() != myTeam) {
                    rc.throwRat();
                    justThrew = rc.getCarrying();
                    justThrewRound = rc.getRoundNum();
                    return;
                }
            }
            if (rc.canSenseLocation(threeFront)) {
                RobotInfo third = rc.senseRobotAtLocation(threeFront);
                if (third == null || third.getTeam() != myTeam) {
                    rc.throwRat();
                    justThrew = rc.getCarrying();
                    justThrewRound = rc.getRoundNum();
                    return;
                }
            }
            rc.dropRat(throwDir);
        }
    }

    static boolean becomeKingRequiredConditions() throws GameActionException {
        int maxKings = rc.getRoundNum() <= GameConstants.RAT_KING_CUTOFF_ROUND ? GameConstants.MAX_NUMBER_OF_RAT_KINGS
                : 2;
        return rc.getAllCheese() - GameConstants.RAT_KING_UPGRADE_CHEESE_COST >= becomeKingThreshold(numRatKings)
                &&
                numRatKings < maxKings && !isNearAllyRatKing() &&
                sensedCats.length == 0 && rc.getRoundNum() >= MIN_ROUNDS_TO_BECOME_KING;
    }

    static boolean becomeKingMineConditions() throws GameActionException {
        int mineCount = sharedArrayMineCount;
        if (symmetry != Symmetry.UNKNOWN) {
            mineCount *= 2;
        }

        mineCount = Math.max(mineCount, mineLocations.size());

        if (mineCount < (numRatKings * 2)) {
            return false;
        }

        return chosenMine != null && arrivedAtChosenMine && becomeKingTileConditions();
    }

    static final int[] offsetX = { -1, 0, 1, 1, 1, 0, -1, -1 };
    static final int[] offsetY = { -1, -1, -1, 0, 1, 1, 1, 0 };

    // Review if necessary
    static boolean becomeKingTileConditions() throws GameActionException {
        if (myLoc.x == 0 || myLoc.x == mapWidth - 1 || myLoc.y == 0 || myLoc.y == mapHeight - 1) {
            return false;
        }

        for (int i = 0; i < offsetX.length; i++) {
            int newX = myLoc.x + offsetX[i];
            int newY = myLoc.y + offsetY[i];
            if (get(newX, newY) == Tile.WALL || get(newX, newY) == Tile.DIRT) {
                return false;
            }
        }
        return true;

    }

    static void checkBecomeKing() throws GameActionException {
        if (rc.canBecomeRatKing()) {
            if (ratKingCenter != null || (becomeKingRequiredConditions() && becomeKingMineConditions())) {
                rc.becomeRatKing();
                mineCooldowns.clear();
                initRatKing();
                senseNearby();
                updateSymmetry();
                manageKingLifecycle();
                listenForSqueaks();
                evadeCat();
                evadeRats();
                return;
            }
        }
    }

    static void handleCarrying() throws GameActionException {
        if (rc.getCarrying() != null && rc.getCarrying().getTeam() == enemyTeam) {
            carryingCounter++;
        } else {
            carryingCounter = 0;
        }

        if (carryingCounter >= GameConstants.MAX_CARRY_DURATION - 2) {
            tryLastResortThrow();
        }
    }

    private static void rotateRandomlyAfterMove() throws GameActionException {
        if (!rc.canTurn())
            return;

        if (startRotateLeft) {
            rc.turn(rc.getDirection().rotateLeft());
            visionChanged = true;

        } else {
            rc.turn(rc.getDirection().rotateRight());
            visionChanged = true;
        }

        startRotateLeft = !startRotateLeft;
    }

    static boolean couldBeUnsafe(Direction dir) throws GameActionException {
        MapLocation newLoc = myLoc.add(dir);
        if (!rc.onTheMap(newLoc)) {
            return false;
        }
        if (rc.canSenseLocation(newLoc)) {
            return false;
        }
        if (!rc.canMove(dir)) {
            Tile tile = get(newLoc);
            if (tile == Tile.WALL || tile == Tile.DIRT) {
                return false;
            }
            return true;
        }
        return false;
    }

    static Direction identifyAmbushDirection() throws GameActionException {
        Direction opposite = rc.getDirection().opposite();
        if (couldBeUnsafe(opposite)) {
            return opposite;
        }

        Direction left = opposite.rotateLeft();
        if (couldBeUnsafe(left)) {
            return opposite;
        }
        Direction right = opposite.rotateRight();
        if (couldBeUnsafe(right)) {
            return opposite;
        }

        Direction leftAgain = left.rotateLeft();
        if (couldBeUnsafe(leftAgain)) {
            return left;
        }
        Direction rightAgain = right.rotateRight();
        if (couldBeUnsafe(rightAgain)) {
            return right;
        }
        return opposite;
    }

    static boolean checkAmbush() throws GameActionException {
        if (sensedEnemies.length > 0 || sensedCats.length > 0) {
            return false;
        }
        if (possibleEnemyLoc != null && possibleEnemyLocDistSq <= 16) {
            return false;
        }
        int diff = prevHealth - rc.getHealth();
        if (diff > 0) {
            if (!rc.isMovementReady() && !rc.canTurn()) {
                // System.out.println("Possible ambush but no action ready");
                return false;
            }

            Direction ambushDir = identifyAmbushDirection();
            if (diff == GameConstants.CAT_SCRATCH_DAMAGE) {
                if (rc.canMove(ambushDir.opposite())) {
                    rc.move(ambushDir.opposite());
                    myLoc = rc.getLocation();
                }
                if (rc.canTurn(ambushDir)) {
                    rc.turn(ambushDir);
                }
                visionChanged = true;
                // System.out.println("Reacting to cat ambush");
            } else {
                if (rc.canTurn(ambushDir)) {
                    rc.turn(ambushDir);
                }
                visionChanged = true;
                // System.out.println("Reacting to rat ambush");
            }
            return true;
        }

        return false;
    }

    static int prevRound = -1;

    // static String logs = "";

    static void runBabyRat() throws GameActionException {
        allyIDs.clear();
        if (prevRound != -1 && rc.getRoundNum() - prevRound != 1) {
            // logs += "\n--------------------BYTECODE EXCEEDED LAST TURN";
            // System.out.println(logs);
            System.out.println("BYTECODE EXCEEDED LAST TURN");
        }
        // logs = "";
        // logs += "\nStart run: " + Clock.getBytecodeNum();
        prevRound = rc.getRoundNum();
        numRatKings = countRatKings();
        myLoc = rc.getLocation();
        syncSharedArray();
        listenForSqueaks();
        senseNearby();

        if (sensedEnemies.length == 0) {
            listenForEnemyLocSqueaks();
        }
        flyingRatDangerSpots.clear();

        if (sensedEnemies.length > 0) {
            justDugForNav = false;
            justDugForNavTurn = -1;
        }

        if (enemyKingLastSeen > rc.getRoundNum() - KING_FORGET_ROUNDS) {
            enemyKingLoc = null;
            enemyKingLastSeen = -1;
        }

        if (numRatKings > prevNumRatKings || !becomeKingRequiredConditions() || sensedCats.length > 0
                || catLastSeenLoc != null && catLastSeenLoc.isWithinDistanceSquared(myLoc, 16)
                        && catLastSeenRound > rc.getRoundNum() - CAT_FORGET_ROUNDS) {
            ratKingCenter = null;
            reachedRatKingCenter = -1;
        }

        prevNumRatKings = numRatKings;

        nearestAllyKingLoc = findNearestRatKing();
        handleCarrying();
        checkBecomeKing();
        checkSOSBits();

        // logs += "\nFinish initial: " + Clock.getBytecodeNum();

        if (!rc.isBeingCarried() && !rc.isBeingThrown()) {
            if (chosenMine != null && arrivedAtChosenMine
                    && rc.getRoundNum() - lastSeenCheese >= CHEESE_TOLERANCE_ROUNDS) {
                mineCooldowns.add(chosenMine);
                chosenMine = null;
                arrivedAtChosenMine = false;
                // logs += "\nHandle mine cooldowns: " + Clock.getBytecodeNum();
            }

            if (currentState == State.COLLECT && ratKingCenter == null) {
                MapLocation formLoc = readFormRatKingLoc();
                if (formLoc != null) {
                    if (numRatKings > prevNumRatKings || !becomeKingRequiredConditions() || sensedCats.length > 0
                            || catLastSeenLoc != null && catLastSeenLoc.isWithinDistanceSquared(myLoc, 16)
                                    && catLastSeenRound > rc.getRoundNum() - CAT_FORGET_ROUNDS) {
                    } else {
                        ratKingCenter = formLoc;
                        reachedRatKingCenter = -1;
                    }
                }
                // logs += "\nCheck form rat king loc: " + Clock.getBytecodeNum();
            }

            State nextState = decideState();
            // logs += "\nDecide state: " + Clock.getBytecodeNum() + " " + nextState;
            if (nextState != currentState) {
                clearDestination();
                currentState = nextState;
            }

            decideDestination();

            // logs += "\nDecide destination: " + Clock.getBytecodeNum();
            tryThrowRat();

            if (sensedEnemies.length > 0) {
            } else if (possibleEnemyLoc != null && possibleEnemyLocDistSq <= 8 && rc.canTurn()) {
                Direction toPossibleEnemy = myLoc.directionTo(possibleEnemyLoc);
                if (rc.canTurn(toPossibleEnemy)) {
                    rc.turn(toPossibleEnemy);
                    visionChanged = true;
                }
            } else if (lastSeenEnemyLoc != null && rc.canTurn()) {
                Direction toLastEnemy = myLoc.directionTo(lastSeenEnemyLoc);
                if (rc.canTurn(toLastEnemy)) {
                    rc.turn(toLastEnemy);
                    visionChanged = true;
                }
            } else {
                checkAmbush();
            }

            if (visionChanged) {
                senseNearbyAllies();
                senseNearbyCats();
                senseNearbyEnemies();
                visionChanged = false;
                // logs += "\nResensed troops after vision changed: " + Clock.getBytecodeNum();
            }

            if (sensedEnemies.length > 0) {
                int nearestDist = Integer.MAX_VALUE;
                for (RobotInfo enemy : sensedEnemies) {
                    int dist = myLoc.distanceSquaredTo(enemy.getLocation());
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        lastSeenEnemyLoc = enemy.getLocation();
                    }
                }
                // logs += "\nUpdate last seen enemy loc: " + Clock.getBytecodeNum();
            } else {
                lastSeenEnemyLoc = null;
            }

            boolean shouldEnterCombat = sensedCats.length > 0
                    || catLastSeenLoc != null && catLastSeenLoc.isWithinDistanceSquared(myLoc, 16)
                            && catLastSeenRound > rc.getRoundNum() - CAT_FORGET_ROUNDS;

            if (sensedEnemies.length > 0 && !shouldEnterCombat) {
                if (canReachEnemyInMoves(myLoc, sensedEnemies, 3)) {
                    // logs += "\nCan reach enemy in moves, entering combat: " +
                    // Clock.getBytecodeNum();
                    shouldEnterCombat = true;
                }
                // logs += "\nFinish check can reach enemy in moves: " + Clock.getBytecodeNum();
            }

            // logs += "\nDecide combat: " + Clock.getBytecodeNum();

            if (shouldEnterCombat) {
                tryDropAllyInCombat();

                // logs += "\nTry drop ally in combat: " + Clock.getBytecodeNum();
                doAttack();
                // logs += "\nTry regular attack: " + Clock.getBytecodeNum();
                doCatAttack();
                // logs += "\nTry cat attack: " + Clock.getBytecodeNum();

                Direction bestDir = getBestMove();

                // Find best direction index in ALL_DIRS to check canRatnapAfterMove
                int bestDirIndex = 0;
                for (int i = 0; i < 9; i++) {
                    if (ALL_DIRS[i] == bestDir) {
                        bestDirIndex = i;
                        break;
                    }
                }

                if (!canRatnapAfterMove[bestDirIndex] && rc.isActionReady()) {
                    handleTrapPlacement();
                    if (!rc.isActionReady()) {
                        bestDir = getBestMoveWithoutActionScores();
                    }
                }

                MapLocation bestLoc = myLoc.add(bestDir);
                // logs += "\nCalculate best move: " + Clock.getBytecodeNum();

                Direction ratnapDir = findRatnapDirection(bestLoc, sensedEnemies);
                Direction defensiveDir = calculateDefensiveDirection(sensedEnemies, emptyArray, bestLoc, bestDir);
                // logs += "\nCalc directioning: " + Clock.getBytecodeNum();

                Direction targetDir = (ratnapDir != null) ? ratnapDir : defensiveDir;

                if (sensedAdjacentEnemies.length > 0 && targetDir != null) {
                    tookDefensiveDirection = true;
                }
                if (bestDir != Direction.CENTER && rc.isMovementReady()) {

                    boolean moveDirIsTarget = bestDir == targetDir;
                    prevSensedEnemies = sensedEnemies;

                    if (moveDirIsTarget && rc.getDirection() != targetDir && rc.canTurn(targetDir)) {
                        rc.turn(targetDir);
                        visionChanged = true;
                    }

                    if (!rc.canMove(bestDir) && rc.isActionReady()) {
                        MapLocation digLoc = myLoc.add(bestDir);
                        if (rc.canRemoveDirt(digLoc)) {
                            rc.removeDirt(digLoc);
                            setTile(Tile.EMPTY, digLoc);
                        }
                    }

                    if (rc.canMove(bestDir)) {
                        rc.move(bestDir);
                        visionChanged = true;
                        myLoc = rc.getLocation();

                        if (visionChanged) {
                            senseNearbyEnemies();
                            visionChanged = false;
                        }

                        if (rc.canTurn()) {
                            if (ratnapDir == null) {
                                ratnapDir = findRatnapDirection(myLoc, sensedEnemies);
                            }
                            if (ratnapDir != null) {
                                targetDir = ratnapDir;
                            } else {
                                targetDir = calculateDefensiveDirection(prevSensedEnemies, sensedEnemies, myLoc,
                                        bestDir);
                            }
                        }
                    }

                    if (rc.getDirection() != targetDir && rc.canTurn(targetDir)) {
                        rc.turn(targetDir);
                        visionChanged = true;
                    }
                }
                // logs += "\nFinish combat: " + Clock.getBytecodeNum();

            } else {
                if (ratKingCenter == myLoc) {
                    squeakBecomeKing();
                } else if (ratKingCenter == null && becomeKingRequiredConditions() && becomeKingMineConditions()
                        && sensedAllies.length > 3) {
                    squeakBecomeKing();
                }
                tryCheeseCourier();
                tryDropAllyNearKing();

                Nav.goTo(destination);
                myLoc = rc.getLocation();

                if (visionChanged) {
                    senseNearbyEnemies();
                    if (sensedEnemies.length > 0) {
                        Direction targetDir = calculateDefensiveDirection(sensedEnemies, emptyArray, myLoc,
                                rc.getDirection());

                        if (targetDir != null && rc.canTurn(targetDir)) {
                            rc.turn(targetDir);
                            visionChanged = true;
                            tookDefensiveDirection = true;
                        }
                    }
                    visionChanged = false;
                }

                // logs += "\nFinish nav etc: " + Clock.getBytecodeNum();
            }
            if (rc.isActionReady()) {
                if (visionChanged) {
                    senseNearbyEnemies();
                    visionChanged = false;

                    // logs += "\nSensed enemies again: " + Clock.getBytecodeNum();
                }

                doAttack();
                handleTrapPlacement();
                // logs += "\nAttacked etc: " + Clock.getBytecodeNum();
                tryCheeseCourier();
                tryDropAllyNearKing();
                // logs += "\nTried cheese stuff: " + Clock.getBytecodeNum();

                if (Clock.getBytecodesLeft() >= 1000) {
                    senseNearbyCats();
                    doCatAttack();
                    // logs += "\nTried cat stuff: " + Clock.getBytecodeNum();
                }
            }

            // logs += "\nFinish last attack checks etc: " + Clock.getBytecodeNum();

            if (rc.canTurn() && !tookDefensiveDirection && sensedEnemies.length == 0) {
                rotateRandomlyAfterMove();
            }
        } else {
            rotateRandomlyAfterMove();
            senseNearby();
            visionChanged = false;
        }

        if (Clock.getBytecodesLeft() >= 1000) {
            if (visionChanged) {
                sensedEnemies = rc.senseNearbyRobots(-1, enemyTeam);
                visionChanged = false;
            }
            if (sensedEnemies.length > 0) {
                MapLocation nearestEnemyLoc = null;
                MapLocation secondNearestEnemyLoc = null;
                int nearestEnemyDistSq = Integer.MAX_VALUE;
                int secondNearestEnemyDistSq = Integer.MAX_VALUE;
                for (RobotInfo enemy : sensedEnemies) {
                    int distSq = myLoc.distanceSquaredTo(enemy.getLocation());
                    if (distSq < nearestEnemyDistSq) {
                        // Current nearest becomes second nearest
                        secondNearestEnemyDistSq = nearestEnemyDistSq;
                        secondNearestEnemyLoc = nearestEnemyLoc;
                        // New nearest
                        nearestEnemyDistSq = distSq;
                        nearestEnemyLoc = enemy.getLocation();
                    } else if (distSq < secondNearestEnemyDistSq) {
                        secondNearestEnemyDistSq = distSq;
                        secondNearestEnemyLoc = enemy.getLocation();
                    }
                }
                if (nearestEnemyLoc != null) {
                    squeakEnemies(nearestEnemyLoc, secondNearestEnemyLoc);
                }
            }

            squeakMineLocations();

            // logs += "\nWrap up optional stuff: " + Clock.getBytecodeNum();
        }

        prevHealth = rc.getHealth();
        visibleCheeseLoc = null;
        visibleAllyKingLoc = null;
        bestCheeseDist = Integer.MAX_VALUE;
        tookDefensiveDirection = false;

        possibleEnemyLoc = null;
        possibleEnemyLocDistSq = Integer.MAX_VALUE;

        if (kingSOS && rc.getRoundNum() - kingSOSRound > KING_SOS_DURATION) {
            kingSOS = false;
        }

        if (ratKingCenter != null && reachedRatKingCenter >= 0
                && rc.getRoundNum() - reachedRatKingCenter > 30) {
            ratKingCenter = null;
            reachedRatKingCenter = -1;
        }

        if (justDugForNav && justDugForNavTurn >= 0 && rc.getRoundNum() - justDugForNavTurn >= 3) {
            justDugForNav = false;
            justDugForNavTurn = -1;
        }

        if (sensedEnemies.length > 0) {
            justDugForNav = false;
            justDugForNavTurn = -1;
        }

        updateSymmetry();

        // logs += "\nFinish final checks: " + Clock.getBytecodeNum();

        if (Clock.getBytecodesLeft() >= 500 && visionChanged) {
            senseNearbyAllies();
            // logs += "\nSense allies again: " + Clock.getBytecodeNum();
        }
    }

    public static void run(RobotController rc) throws GameActionException {
        initRobotPlayer(rc);

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
                        // kingInfo();
                        break;
                    default:
                        runBabyRat();
                        // babyRatInfo();
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
}
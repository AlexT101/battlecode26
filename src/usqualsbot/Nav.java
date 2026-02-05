package usqualsbot;

import static usqualsbot.Globals.*;
import static usqualsbot.Map.*;

import battlecode.common.*;
import usqualsbot.Map.Tile;

public class Nav {
    public static MapLocation destination;
    public static boolean destinationExact;

    public static void setDestination(MapLocation newTarget, boolean exact) {
        destination = newTarget;
        destinationExact = exact;
    }

    public static void clearDestination() {
        destination = null;
        destinationExact = false;
    }

    static int[][] states;
    static char bugPathIndex = 0;
    static Boolean rotateRight = null;
    static MapLocation lastObstacleFound = null;
    static MapLocation prevTarget = null;
    static int minDistToTarget = Integer.MAX_VALUE;
    static MapLocation minLocationToTarget = null;
    static int turnsMovingToObstacle = 0;

    static final int INF = Integer.MAX_VALUE;
    static final int MAX_TURNS_MOVING_TO_OBSTACLE = 2;
    static final int MIN_DIST_RESET = 3;

    static void init() {
        if (states == null) {
            states = new int[rc.getMapWidth()][rc.getMapHeight()];
        }
    }

    public static void goTo(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady())
            return;

        init();

        if (target == null)
            target = myLoc;

        if (prevTarget == null) {
            resetPathfinding();
            rotateRight = null;
        } else {
            int distTargets = target.distanceSquaredTo(prevTarget);
            if (distTargets > 0) {
                if (distTargets >= MIN_DIST_RESET) {
                    rotateRight = null;
                    resetPathfinding();
                } else {
                    softReset(target);
                }
            }
        }

        prevTarget = target;
        checkState();

        int d = myLoc.distanceSquaredTo(target);
        if (d == 0)
            return;

        if (d < minDistToTarget) {
            resetPathfinding();
            minDistToTarget = d;
            minLocationToTarget = myLoc;
        }

        Direction dir = myLoc.directionTo(target);

        if (lastObstacleFound == null) {
            if (tryGreedyMove()) {
                resetPathfinding();
                return;
            }
        } else {
            dir = myLoc.directionTo(lastObstacleFound);
        }

        if (canPass(dir)) {
            executeMove(dir);
            if (lastObstacleFound != null) {
                ++turnsMovingToObstacle;
                lastObstacleFound = myLoc.add(dir);
                if (turnsMovingToObstacle >= MAX_TURNS_MOVING_TO_OBSTACLE) {
                    resetPathfinding();
                } else if (!rc.onTheMap(lastObstacleFound)) {
                    resetPathfinding();
                }
            }
            return;
        } else {
            turnsMovingToObstacle = 0;
        }

        checkRotate(dir);

        int i = 16;
        while (i-- > 0) {
            if (canPass(dir)) {
                executeMove(dir);
                return;
            }
            MapLocation newLoc = myLoc.add(dir);
            if (!rc.onTheMap(newLoc)) {
                rotateRight = !rotateRight;
            } else {
                lastObstacleFound = newLoc;
            }

            if (rotateRight)
                dir = dir.rotateRight();
            else
                dir = dir.rotateLeft();
        }

        if (canPass(dir)) {
            executeMove(dir);
        }
    }

    static boolean tryGreedyMove() throws GameActionException {
        Direction dir = myLoc.directionTo(prevTarget);
        if (canPass(dir)) {
            executeMove(dir);
            return true;
        }

        int dist = myLoc.distanceSquaredTo(prevTarget);
        int dist1 = INF, dist2 = INF;

        Direction dir1 = dir.rotateRight();
        if (canPass(dir1))
            dist1 = myLoc.add(dir1).distanceSquaredTo(prevTarget);

        Direction dir2 = dir.rotateLeft();
        if (canPass(dir2))
            dist2 = myLoc.add(dir2).distanceSquaredTo(prevTarget);

        if (dist1 < dist && dist1 < dist2) {
            executeMove(dir1);
            return true;
        }
        if (dist2 < dist && dist2 < dist1) {
            executeMove(dir2);
            return true;
        }
        return false;
    }

    static void checkRotate(Direction dir) throws GameActionException {
        if (rotateRight != null)
            return;
        Direction dirLeft = dir;
        Direction dirRight = dir;

        int i = 8;
        while (--i >= 0) {
            if (!canPass(dirLeft))
                dirLeft = dirLeft.rotateLeft();
            else
                break;
        }
        i = 8;
        while (--i >= 0) {
            if (!canPass(dirRight))
                dirRight = dirRight.rotateRight();
            else
                break;
        }

        int distLeft = myLoc.add(dirLeft).distanceSquaredTo(prevTarget);
        int distRight = myLoc.add(dirRight).distanceSquaredTo(prevTarget);

        if (distRight < distLeft)
            rotateRight = true;
        else
            rotateRight = false;
    }

    static void resetPathfinding() {
        lastObstacleFound = null;
        minDistToTarget = INF;
        ++bugPathIndex;
        turnsMovingToObstacle = 0;
    }

    static void softReset(MapLocation target) {
        if (minLocationToTarget != null)
            minDistToTarget = minLocationToTarget.distanceSquaredTo(target);
        else
            resetPathfinding();
    }

    static void checkState() {
        int x, y;
        if (lastObstacleFound == null) {
            x = 61;
            y = 61;
        } else {
            x = lastObstacleFound.x;
            y = lastObstacleFound.y;
        }

        char state = (char) ((bugPathIndex << 14) | (x << 8) | (y << 2));
        if (rotateRight != null) {
            if (rotateRight)
                state |= 1;
            else
                state |= 2;
        }

        if (states[myLoc.x][myLoc.y] == state) {
            resetPathfinding();
        }

        states[myLoc.x][myLoc.y] = state;
    }

    // Returns true if we can move OR if it is dirt and we have enough cheese to dig
    // it.
    static boolean canPass(Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER)
            return false;

        if (rc.canMove(dir))
            return true;

        MapLocation next = myLoc.add(dir);
        if (rc.onTheMap(next) && rc.canSenseLocation(next)) {
            MapInfo info = rc.senseMapInfo(next);
            if (info.isDirt()) {
                return (justDugForNav || rc.canRemoveDirt(next)) && rc.getGlobalCheese() > CHEESE_EMERGENCY_THRESHOLD;
            }
        }
        return false;
    }

    static void executeMove(Direction dir) throws GameActionException {
        if (dir != rc.getDirection() && rc.canTurn(dir)) {
            rc.turn(dir);
            visionChanged = true;
        }
        if (rc.canMove(dir)) {
            rc.move(dir);
            visionChanged = true;
            myLoc = rc.getLocation();
        } else {
            MapLocation next = myLoc.add(dir);
            if (rc.canSenseLocation(next)) {
                MapInfo info = rc.senseMapInfo(next);
                if (info.isDirt() && rc.canRemoveDirt(next) && rc.getGlobalCheese() > CHEESE_EMERGENCY_THRESHOLD) {
                    rc.removeDirt(next);
                    setTile(Tile.EMPTY, next);
                    justDugForNav = true;
                    justDugForNavTurn = rc.getRoundNum();
                    if (rc.canMove(dir)) {
                        rc.move(dir);
                        visionChanged = true;
                        myLoc = rc.getLocation();
                    }
                }
            }
        }
    }
}
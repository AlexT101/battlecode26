package usqualsbot;

import static usqualsbot.Globals.*;
import static usqualsbot.Map.*;
import static usqualsbot.Nav.*;

import java.util.Arrays;

import battlecode.common.*;

public class Visualizer {
    static Direction bugnavRecommended;
    static MapLocation prevLoc;

    static void kingInfo() throws GameActionException {
        int[] sharedArray = new int[64];
        for (int i = 0; i < 64; i++) {
            sharedArray[i] = rc.readSharedArray(i);
        }
        rc.setIndicatorString("Cheese: " + rc.getGlobalCheese() + "\nShared: " + Arrays.toString(sharedArray) + "\nDest: " + destination);
    }

    static void babyRatInfo() throws GameActionException {
         rc.setIndicatorString(currentState.toString() + "\n" + "Dest: " + destination + "\nHeuristic: " + Arrays.toString(scores));
    }

    static void babyRatVisualizer() throws GameActionException {
        for (int x = 0; x < mapWidth; x++) {
            for (int y = 0; y < mapHeight; y++) {
                if (get(x, y) != null) {
                    rc.setIndicatorDot(new MapLocation(x, y), 255, 255, 0);
                }
            }
        }

        if (destination != null) {
            int blockX = destination.x / VISITED_BLOCK_DIMENSION;
            int blockY = destination.y / VISITED_BLOCK_DIMENSION;
            for (int bx = 0; bx < VISITED_BLOCK_DIMENSION; bx++) {
                for (int by = 0; by < VISITED_BLOCK_DIMENSION; by++) {
                    int drawX = blockX * VISITED_BLOCK_DIMENSION + bx;
                    int drawY = blockY * VISITED_BLOCK_DIMENSION + by;
                    if (rc.onTheMap(new MapLocation(drawX, drawY))) {
                        rc.setIndicatorDot(new MapLocation(drawX, drawY), 0, 200, 0);
                    }
                }
            }

            if (prevLoc != null) {
                rc.setIndicatorDot(prevLoc, 255, 0, 0);
                rc.setIndicatorLine(prevLoc, destination, 255, 0, 0);

                if (bugnavRecommended != null) {
                    MapLocation bugnavLoc = prevLoc.add(bugnavRecommended);
                    if (rc.onTheMap(bugnavLoc)) {
                        rc.setIndicatorDot(bugnavLoc, 0, 0, 255);
                    }
                }
            }
        }
        prevLoc = myLoc;
    }
}

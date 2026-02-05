package finalsbot;

import static finalsbot.Globals.*;
import static finalsbot.Nav.*;

import java.util.Arrays;

import battlecode.common.*;

public class Visualizer {
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
}

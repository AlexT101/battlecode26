package finalsbot;

import java.util.Random;
import battlecode.common.*;

public class Globals {
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

    static final Direction[] ALL_DIRS = Direction.allDirections();

    static enum State {
        NONE,
        FIND_KING,
        EXPLORE,
        COLLECT,
        RETURN,
        BUILD_RAT_KING
    }

    static final RobotInfo[] emptyArray = new RobotInfo[0];

    static final int MIN_ALIVE_RATS = 8;
    static final int MIN_ALIVE_RATS_WHEN_ON_CHEESE_MINE = 4;
    static final int MAX_ALIVE_RATS = 16;
    static final int CHEESE_SURPLUS_THRESHOLD = 2000;
    static final int MAX_ALIVE_RATS_CHEESE_SURPLUS = 22;
    static final int LOTS_OF_CHEESE_THRESHOLD = 2500;
    static final int MAX_ALIVE_RATS_LOTS_OF_CHEESE = 32;
    static final int CRAZY_AMOUNTS_OF_CHEESE_THRESHOLD = 3800;
    static final int MAX_ALIVE_RATS_CRAZY_AMOUNTS_OF_CHEESE = 42;
    static final int CHEESE_EMERGENCY_THRESHOLD = 600;
    static final int CHEESE_CRITICAL_THRESHOLD = 150;
    static final int MAX_CHEESE_CARRIED_NEXT_TO_KING = 500;
    static final int MAX_CHEESE_CARRIED_NEAR_KING = 140;
    static final int MAX_CHEESE_CARRIED = 100;
    static final int MAX_CHEESE_TO_RETURN = 40;
    static final int CAT_FORGET_ROUNDS = 4;
    static final int KING_FORGET_ROUNDS = 8;
    static final int KING_CAT_FORGET_ROUNDS = 8;
    static final int MIN_ROUNDS_TO_BECOME_KING = 100;

    static final int ROUNDS_BEFORE_CUTOFF_PANIC_SPAWN = 150;

    static final int MIN_SQUARED_DIST_FROM_KING_TO_SPAWN = 40;

    // HEURISTICS: Used in calculating movement scores during active combat

    // Obstacles
    static final int HEURISTIC_MIN = -99999;
    static final int HEURISTIC_DIRT_TILE = -60;

    // Flee Danger
    static final int HEURISTIC_CAT_ADJACENT = -200;
    static final int HEURISTIC_CAT_ONE_TILE = -80;
    static final int HEURISTIC_CAT_NOT_LOOKING_DIVIDER = 10;
    static final int HEURISTIC_CAT_PREVIOUS = -50;

    static final int HEURISTIC_ENEMY_RAT_ADJACENT = -10;
    static final int HEURISTIC_ENEMY_RAT_ONE_TILE = -5;
    static final int HEURISTIC_ENEMY_RAT_ADJACENT_HIGHER_HP = -15;
    static final int HEURISTIC_ENEMY_RAT_ONE_TILE_HIGHER_HP = -10;

    static final int HEURISTIC_HEALTH_DEFICIT_MULTIPLIER = 0;

    static final int HEURISTIC_IS_ENEMY_RAT_CARDINAL = -5;

    static final int HEURISTIC_FLYING_RAT_DANGER = -10;

    // Swarm King
    static final int HEURISTIC_ENEMY_KING_ADJACENT = 50;
    static final int HEURISTIC_ENEMY_KING_ONE_TILE = 20;

    // Attack Opportunity
    static final int HEURISTIC_RATNAP_POSITION_BONUS = 50;
    static final int HEURISTIC_RATNAP_HP_BONUS = 35;
    static final int HEURISTIC_ATTACK_KING_BONUS = 30;
    static final int HEURISTIC_FINISHER_ATTACK_BONUS = 20;
    static final int HEURISTIC_READY_ATTACK_BONUS = 10;
    static final int HEURISTIC_ENEMY_RAT_ADJACENT_JUST_THREW = 15;
    static final int HEURISTIC_ENEMY_RAT_ONE_TILE_JUST_THREW = 5;

    // Strength in Numbers
    static final int HEURISTIC_ALLY_RAT_ADJACENT = 0;
    static final int HEURISTIC_ALLY_RAT_ONE_TILE = 0;

    // Exploration and Movement
    //static final int HEURISTIC_UNVISITED_TILE = 0;
    static final int HEURISTIC_DESTINATION_MULTIPLIER = 3;
    static final int HEURISTIC_STANDING_STILL = 0;
    //static final int HEURISTIC_ADJACENT_CHEESE_MULTIPLIER = 0;

    static final int HEURISTIC_STRAFE_PENALTY = -5;

    static FastSetPrecise flyingRatDangerSpots = new FastSetPrecise();

    static boolean visionChanged = false;
    static boolean justDugForNav = false;
    static int justDugForNavTurn = -1;

    static MapLocation ratKingCenter = null;
    static int reachedRatKingCenter = -1;
    static int numRatKings = 0;
    static int prevNumRatKings = 0;

    static MapLocation lastSeenEnemyLoc;

    static FastSet existingMineLocs = new FastSet();

    // Enemy location from squeaks (reset each turn)
    static MapLocation possibleEnemyLoc = null;
    static int possibleEnemyLocDistSq = Integer.MAX_VALUE;

    static int lastSpawnedRound = -1;

    static boolean tookDefensiveDirection = false;

    // Anti-stuck mechanism
    static final int CARRIED_DISINTEGRATE_THRESHOLD = 12;
    static int carriedRoundsCounter = 0;

    // Endgame thresholds
    static final int AGGRESSION_ROUND = 600;

    static Random rng;

    static RobotController rc;

    static Team myTeam;
    static Team enemyTeam;

    static State currentState = State.NONE;

    static MapLocation myLoc;

    static MapInfo[] sensedTiles = new MapInfo[0];
    static RobotInfo[] sensedAllies = new RobotInfo[0];
    static RobotInfo[] sensedEnemies = new RobotInfo[0];
    static RobotInfo[] prevSensedEnemies = new RobotInfo[0];
    static RobotInfo[] sensedCats = new RobotInfo[0];
    static RobotInfo[] sensedAdjacentEnemies = new RobotInfo[0];

    static int prevHealth = -1;

    static MapLocation enemyKingLoc = null;
    static int enemyKingLastSeen = -1;
    static MapLocation catLastSeenLoc = null;
    static int catLastSeenRound = -1;

    static int sharedArrayMineCount = 0;
    static FastSet mineLocations = new FastSet();
    static FastSet mineCooldowns = new FastSet();
    static int lastSeenCheese = 0;
    static int prevNumberOfMines = 0;
    static MapLocation chosenMine = null;
    static boolean arrivedAtChosenMine = false;

    static FastSet allyIDs = new FastSet();

    static final int CHEESE_TOLERANCE_ROUNDS = 3;

    static MapLocation[] possibleEnemySpawns = new MapLocation[3];
    static int currentTargetIndex = -1;
    static boolean[] spawnChecked = new boolean[3];

    // Only used if unit is a RAT_KING
    static int myKingIndex = -1;
    static int initialCatTraps = 0;
    static MapLocation kingStartLoc = null; // King's starting position for halfway-to-center navigation

    // Only used if unit is a BABY_RAT
    static boolean kingSOS = false;
    static int kingSOSRound = 0;
    static final int KING_SOS_DURATION = 2;

    static boolean[] prevAliveBitState = new boolean[5];
    static boolean[] kingIsAlive = new boolean[5];

    static MapLocation visibleCheeseLoc;
    static MapLocation visibleAllyKingLoc;
    static MapLocation nearestAllyKingLoc;
    static int bestCheeseDist = Integer.MAX_VALUE;

    static MapLocation centerLocation;

    static int carryingCounter = 0;

    static MapLocation[] currentKingLocs = new MapLocation[5];

    static boolean startRotateLeft;

    static RobotInfo justThrew;
    static int justThrewRound = -1;
    static final int THRESHOLD_FOR_JUST_THREW = 3;

    static int[] scores = new int[9];
    static int[] actionScores = new int[9];
    static boolean[] canRatnapAfterMove = new boolean[9];
    static boolean[] impassable = new boolean[9];

    static MapLocation[] trapLocations = new MapLocation[40];
    static int numTraps = 0;

}

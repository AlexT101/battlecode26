package usqualsbot;

import static usqualsbot.Globals.*;
import static usqualsbot.Map.*;

import battlecode.common.*;
import usqualsbot.Map.Symmetry;

public class Comms {
    static final int SHARED_SYMMETRY_INDEX = 0;
    static final int SHARED_STARTING_KING_POSITION_INDEX = 1;
    static final int SHARED_CURRENT_KING_POSITION_INDEX = 2; // Base index, king i is at index 1+i, up to 5 kings
    static final int SHARED_ALIVE_BITS_INDEX = 7;
    static final int SHARED_SOS_BITS_INDEX = 8;
    static final int SHARED_FORM_RAT_KING_LOC_INDEX = 9; // Lossy encoded location for forming new rat king (0 = none)
    static final int SHARED_MINE_COUNT_INDEX = 10; // Number of mines stored in shared array
    static final int SHARED_MINES_START_INDEX = 11; // Mine locations start here, up to index 63

    static final int SQUEAK_TYPE_BECOME_KING = 0;
    static final int SQUEAK_TYPE_ENEMY_KING = 1;
    static final int SQUEAK_TYPE_CHEESE_MINE = 2;
    static final int SQUEAK_TYPE_SYMMETRY = 3;
    static final int SQUEAK_TYPE_ENEMY = 4;

    // Lossy encoding parameters - initialized once based on map dimensions
    static boolean lossyEncodingInitialized = false;
    static int lossyDivisor; // 1 = no division (full precision), 2 = divide by 2
    static int lossyHeightMod; // Number of Y values after division (for modular arithmetic)

    /**
     * Initialize lossy encoding parameters based on map dimensions.
     * Called automatically on first encode/decode, or can be called explicitly.
     * For maps where width*height <= 1024, no precision is lost.
     * For larger maps, both dimensions are divided by 2.
     */
    static void initLossyEncoding() {
        if (lossyEncodingInitialized)
            return;

        if (mapWidth * mapHeight <= 1024) {
            // No division needed - can encode with full precision
            lossyDivisor = 1;
            lossyHeightMod = mapHeight;
        } else {
            // Need to divide both dimensions by 2
            lossyDivisor = 2;
            lossyHeightMod = (mapHeight + 1) / 2;
        }

        lossyEncodingInitialized = true;
    }

    /**
     * Encode a location into a smaller integer (1-1024).
     * Uses adaptive precision based on map size:
     * - For small maps (width*height <= 1024): no precision loss
     * - For larger maps: coordinates are divided by 2
     * Format: reducedX * lossyHeightMod + reducedY + 1
     * Adding 1 ensures 0 represents "no king" / missing value.
     */
    static char encodeLocation(MapLocation loc) {
        int reducedX = loc.x / lossyDivisor;
        int reducedY = loc.y / lossyDivisor;
        return (char) (reducedX * lossyHeightMod + reducedY + 1);
    }

    /**
     * Decode an encoded location back to MapLocation.
     * Uses the same adaptive precision as encodeLocation.
     */
    static MapLocation decodeLocation(int encoded) {
        int raw = encoded - 1;
        int reducedX = raw / lossyHeightMod;
        int reducedY = raw % lossyHeightMod;
        return new MapLocation(reducedX * lossyDivisor, reducedY * lossyDivisor);
    }

    /**
     * Encode a location with full precision (no loss).
     * Format: x (6 bits) | y (6 bits) = 12 bits total
     * x and y can range from 0 to 59 (fits in 6 bits each).
     */
    static char encodeFullLocation(MapLocation loc) {
        return (char) ((loc.x << 6) | loc.y);
    }

    /**
     * Decode a full precision encoded location back to MapLocation.
     */
    static MapLocation decodeFullLocation(int encoded) {
        int x = (encoded >> 6) & 0x3F; // top 6 bits
        int y = encoded & 0x3F; // bottom 6 bits
        return new MapLocation(x, y);
    }

    // --- ALIVE AND SOS BIT METHODS ---

    /**
     * Reads the entire integer containing the alive bits for all kings.
     * Use this to check multiple kings efficiently.
     */
    static int readAliveBits() throws GameActionException {
        return rc.readSharedArray(SHARED_ALIVE_BITS_INDEX);
    }

    /**
     * Reads the entire integer containing the SOS bits for all kings.
     */
    static int readSOSBits() throws GameActionException {
        return rc.readSharedArray(SHARED_SOS_BITS_INDEX);
    }

    static void toggleAliveBit(int kingIndex) throws GameActionException {
        if (kingIndex < 0 || kingIndex >= 5)
            return;
        int bits = rc.readSharedArray(SHARED_ALIVE_BITS_INDEX);
        bits ^= (1 << kingIndex); // Toggle bit
        rc.writeSharedArray(SHARED_ALIVE_BITS_INDEX, bits);
    }

    static boolean readAliveBit(int kingIndex) throws GameActionException {
        if (kingIndex < 0 || kingIndex >= 5)
            return false;
        int bits = rc.readSharedArray(SHARED_ALIVE_BITS_INDEX);
        return (bits & (1 << kingIndex)) != 0;
    }

    static void setSOSBit(int kingIndex, boolean on) throws GameActionException {
        if (kingIndex < 0 || kingIndex >= 5)
            return;
        int bits = rc.readSharedArray(SHARED_SOS_BITS_INDEX);
        if (on) {
            bits |= (1 << kingIndex);
        } else {
            bits &= ~(1 << kingIndex);
        }
        rc.writeSharedArray(SHARED_SOS_BITS_INDEX, bits);
    }

    static boolean readSOSBit(int kingIndex) throws GameActionException {
        if (kingIndex < 0 || kingIndex >= 5)
            return false;
        int bits = rc.readSharedArray(SHARED_SOS_BITS_INDEX);
        return (bits & (1 << kingIndex)) != 0;
    }

    static Symmetry readSymmetry() throws GameActionException {
        int symmetryOrdinal = rc.readSharedArray(SHARED_SYMMETRY_INDEX);
        return Symmetry.values()[symmetryOrdinal];
    }

    static void writeSymmetry(int symmetryOrdinal) throws GameActionException {
        if (rc.getType() == UnitType.RAT_KING) {
            rc.writeSharedArray(SHARED_SYMMETRY_INDEX, symmetryOrdinal);
            writeCondensedMines();
        }
    }

    static void writeSymmetry(Symmetry symmetry) throws GameActionException {
        writeSymmetry(symmetry.ordinal());
    }

    /**
     * Store a mine location in the shared array (only called by king).
     * Avoids duplicates and symmetrical duplicates (if symmetry is known).
     * Also updates the mine count at SHARED_MINE_COUNT_INDEX.
     */
    static void writeMineLocation(MapLocation mineLoc) throws GameActionException {
        if (mineLoc == null) {
            return;
        }
        char encodedMine = Comms.encodeLocation(mineLoc);

        if (existingMineLocs.contains(encodedMine)) {
            return;
        }

        if (symmetry != Symmetry.UNKNOWN) {
            MapLocation symLoc = getSymmetricLocation(mineLoc, symmetry);
            char symmetricalEncoded = Comms.encodeLocation(symLoc);
            if (existingMineLocs.contains(symmetricalEncoded)) {
                return;
            }
        }

        int nextSlot = SHARED_MINES_START_INDEX + sharedArrayMineCount;
        if (nextSlot >= GameConstants.SHARED_ARRAY_SIZE) {
            return;
        }

        rc.writeSharedArray(nextSlot, encodedMine);
        rc.writeSharedArray(SHARED_MINE_COUNT_INDEX, sharedArrayMineCount + 1);

        // Update local state to prevent duplicates and slot overwrites within the same
        // turn
        existingMineLocs.add(encodedMine);
        sharedArrayMineCount++;
    }

    // TODO: Verify this works properly
    /**
     * Condense the mine array by removing symmetrical duplicates.
     * Called when symmetry is first determined (by king only).
     * Ensures mines are continuous with no gaps, followed by 0s.
     * Also updates the mine count at SHARED_MINE_COUNT_INDEX.
     */
    static void writeCondensedMines() throws GameActionException {
        if (symmetry == Symmetry.UNKNOWN)
            return;
        if (rc.getType() != UnitType.RAT_KING)
            return;

        int currentMineCount = rc.readSharedArray(SHARED_MINE_COUNT_INDEX);

        // Use FastSet for duplicate tracking
        int[] uniqueMines = new int[currentMineCount];
        int uniqueCount = 0;

        for (int i = 0; i < currentMineCount; i++) {
            int storedValue = rc.readSharedArray(SHARED_MINES_START_INDEX + i);
            if (storedValue == 0) {
                break;
            }

            char encodedValue = (char) storedValue;
            if (existingMineLocs.contains(encodedValue)) {
                continue;
            }

            // Check symmetric counterpart
            MapLocation mineLoc = Comms.decodeLocation(storedValue);
            MapLocation symLoc = getSymmetricLocation(mineLoc, symmetry);
            char symmetricalEncoded = Comms.encodeLocation(symLoc);

            if (existingMineLocs.contains(symmetricalEncoded)) {
                continue;
            }

            uniqueMines[uniqueCount++] = storedValue;
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

    // =========================================================================
    // UPDATED SQUEAK ENCODING (4 Bits for Type)
    // =========================================================================

    /**
     * Base helper to pack a message type and a 28-bit data payload.
     * Format: [type (4 bits)][data (28 bits)]
     */
    static int encodeSqueakRaw(int messageType, int data) {
        // Shift 28 bits to make room for 4 bits of type (0-15)
        // Mask data with 0x0FFFFFFF (28 ones) to ensure it fits
        return (messageType << 28) | (data & 0x0FFFFFFF);
    }

    /**
     * Encode a squeak message with a type and location.
     */
    static int encodeSqueak(int messageType, MapLocation loc) {
        return encodeSqueakRaw(messageType, Comms.encodeLocation(loc));
    }

    /**
     * Get the message type from an encoded squeak.
     * Uses top 4 bits.
     */
    static int getSqueakType(int squeak) {
        // Shift 28 bits and mask with 0x0F (binary 1111)
        return (squeak >> 28) & 0x0F;
    }

    /**
     * Get the location from an encoded squeak.
     * Uses lower 28 bits.
     */
    static MapLocation getSqueakLocation(int squeak) {
        // Mask with 0x0FFFFFFF (28 bits of 1s)
        return Comms.decodeLocation(squeak & 0x0FFFFFFF);
    }

    /**
     * Get raw data (like enemy count) from an encoded squeak.
     * Uses lower 28 bits.
     */
    static int getSqueakData(int squeak) {
        return squeak & 0x0FFFFFFF;
    }

    // =========================================================================

    /**
     * Squeak the enemy rat king location to nearby allies.
     * DO NOT squeak while evading - cats can hear squeaks!
     * Squeaks every turn when attacking king to ensure info propagates.
     * Uses full precision encoding to preserve exact coordinates.
     */
    static void squeakEnemyKingLocation() throws GameActionException {
        if (enemyKingLoc != null) {
            rc.squeak(encodeSqueakRaw(SQUEAK_TYPE_ENEMY_KING, encodeFullLocation(enemyKingLoc)));
        }
    }

    static void listenForSqueaks() throws GameActionException {
        if (rc.getType() == UnitType.RAT_KING) {
            kingListenForSqueaks();
        } else {
            babyRatListenForSqueaks();
        }
    }

    /**
     * Listen for squeaks and process based on message type.
     * If we're further from the enemy king than the sender, re-squeak to propagate
     * info.
     */
    static void babyRatListenForSqueaks() throws GameActionException {
        int currentRound = rc.getRoundNum();
        boolean shouldResqueak = false;

        // Check squeaks from last rounds
        for (int r = Math.max(0, currentRound - 1); r <= currentRound; r++) {
            Message[] messages = rc.readSqueaks(r);
            for (Message msg : messages) {
                if (msg.getSenderID() == rc.getID()) {
                    continue;
                }
                shouldResqueak |= processSqueak(msg);
            }
        }

        if (shouldResqueak && enemyKingLoc != null) {
            squeakEnemyKingLocation();
        }
    }

    static void squeakBecomeKing() throws GameActionException {
        rc.squeak(encodeSqueak(SQUEAK_TYPE_BECOME_KING, myLoc));
        ratKingCenter = myLoc;
        reachedRatKingCenter = rc.getRoundNum();
    }

    /**
     * Squeak an enemy location with full precision encoding.
     * Uses SQUEAK_TYPE_ENEMY with encodeFullLocation to preserve exact coordinates.
     */
    static void squeakEnemy(MapLocation loc) throws GameActionException {
        rc.squeak(encodeSqueakRaw(SQUEAK_TYPE_ENEMY, encodeFullLocation(loc)));
    }

    /**
     * Squeak a mine location that isn't in the shared array.
     * Only squeaks if sensedEnemies and sensedCats are empty (safe to squeak).
     * Uses lossy encoding like other mine-related squeaks.
     */
    static void squeakMineLocations() throws GameActionException {
        if (sensedEnemies.length > 0 || sensedCats.length > 0) {
            return;
        }

        for (char encodedMine : mineLocations) {
            if (!existingMineLocs.contains(encodedMine)) {
                if (symmetry != Symmetry.UNKNOWN) {
                    MapLocation symLoc = getSymmetricLocation(decodeLocation(encodedMine), symmetry);
                    char symmetricalEncoded = Comms.encodeLocation(symLoc);
                    if (existingMineLocs.contains(symmetricalEncoded)) {
                        continue;
                    }
                }
                rc.squeak(encodeSqueak(SQUEAK_TYPE_CHEESE_MINE, decodeLocation(encodedMine)));
                return;
            }
        }
    }

    /**
     * Handle enemy info received from a squeak.
     * Called when processing SQUEAK_TYPE_ENEMY messages.
     * Updates possibleEnemyLoc if this location is closer than the current
     * candidate.
     */
    static void handleEnemyInfo(MapLocation loc) {
        int distSq = myLoc.distanceSquaredTo(loc);
        if (distSq < possibleEnemyLocDistSq) {
            possibleEnemyLoc = loc;
            possibleEnemyLocDistSq = distSq;
        }
    }

    static boolean processSqueak(Message msg) throws GameActionException {
        int squeakData = msg.getBytes();
        int messageType = getSqueakType(squeakData);
        MapLocation senderLoc = msg.getSource();

        if (messageType == SQUEAK_TYPE_BECOME_KING) {
            ratKingCenter = senderLoc;
            return true;
        } else if (messageType == SQUEAK_TYPE_SYMMETRY) {
            if (symmetry == Symmetry.UNKNOWN) {
                int symmetryOrdinal = getSqueakSymmetryOrdinal(squeakData);
                if (symmetryOrdinal >= 1 && symmetryOrdinal <= 3) {
                    canFlipX = symmetryOrdinal == Symmetry.FLIP_X.ordinal();
                    canFlipY = symmetryOrdinal == Symmetry.FLIP_Y.ordinal();
                    canRotate = symmetryOrdinal == Symmetry.ROTATE.ordinal();
                    updateSymmetry();
                }
            }
        } else if (messageType == SQUEAK_TYPE_ENEMY_KING) {
            MapLocation decodedLoc = decodeFullLocation(getSqueakData(squeakData));
            // Handle enemy king location squeak
            if (enemyKingLoc == null || msg.getRound() > enemyKingLastSeen) {
                enemyKingLoc = decodedLoc;
                enemyKingLastSeen = msg.getRound();

                // Check if we should propagate this squeak
                if (senderLoc != null) {
                    int senderDistToKing = senderLoc.distanceSquaredTo(decodedLoc);
                    int myDistToKing = myLoc.distanceSquaredTo(decodedLoc);

                    if (myDistToKing > senderDistToKing) {
                        return true;
                    }
                }
            }
        } else if (messageType == SQUEAK_TYPE_ENEMY) {
            MapLocation enemyLoc = decodeFullLocation(getSqueakData(squeakData));
            handleEnemyInfo(enemyLoc);
        } else if (messageType == SQUEAK_TYPE_CHEESE_MINE) {
            // Baby rats also listen for mine locations from other rats
            MapLocation mineLoc = getSqueakLocation(squeakData);
            char encodedMine = encodeLocation(mineLoc);
            mineLocations.add(encodedMine);
            // Also add symmetrical location if symmetry is known
            if (symmetry != Symmetry.UNKNOWN) {
                MapLocation symLoc = getSymmetricLocation(mineLoc, symmetry);
                char symmetricalEncoded = encodeLocation(symLoc);
                mineLocations.add(symmetricalEncoded);
            }
        }

        return false;
    }

    static void kingListenForSqueaks() throws GameActionException {
        Message[] messages = rc.readSqueaks(rc.getRoundNum() - 1);
        for (Message msg : messages) {
            int squeakData = msg.getBytes();
            int messageType = getSqueakType(squeakData);

            if (messageType == SQUEAK_TYPE_CHEESE_MINE) {
                Comms.writeMineLocation(getSqueakLocation(squeakData));
            } else if (messageType == SQUEAK_TYPE_SYMMETRY) {
                Symmetry sharedSymmetry = Comms.readSymmetry();
                if (sharedSymmetry == Symmetry.UNKNOWN) {
                    int symmetryOrdinal = getSqueakSymmetryOrdinal(squeakData);
                    if (symmetryOrdinal >= 1 && symmetryOrdinal <= 3) {
                        symmetry = Symmetry.values()[symmetryOrdinal];
                        Comms.writeSymmetry(symmetryOrdinal);
                    }
                }
            }
        }
    }

    /**
     * Encode a symmetry squeak message.
     * Format: [SQUEAK_TYPE_SYMMETRY (4 bits)][symmetry ordinal (28 bits)]
     */
    static int encodeSymmetrySqueak(int symmetryOrdinal) {
        return encodeSqueakRaw(SQUEAK_TYPE_SYMMETRY, symmetryOrdinal);
    }

    /**
     * Get the symmetry ordinal from an encoded symmetry squeak.
     * Uses lower 28 bits.
     */
    static int getSqueakSymmetryOrdinal(int squeak) {
        return squeak & 0x0FFFFFFF;
    }

    static boolean haveNewSymmetryInfo() throws GameActionException {
        int sharedSymmetry = rc.readSharedArray(SHARED_SYMMETRY_INDEX);
        return (symmetry != Symmetry.UNKNOWN && sharedSymmetry == 0);
    }

    /**
     * If we can see an ally rat king, squeak important info.
     * Priority: symmetry (if known and not in shared array), then mines.
     */
    static void squeakToKing() throws GameActionException {
        if (haveNewSymmetryInfo()) {
            rc.squeak(Comms.encodeSymmetrySqueak(symmetry.ordinal()));
            return;
        }

        squeakMineLocations();
    }

    static void syncSharedArray() throws GameActionException {
        if (symmetry == Symmetry.UNKNOWN) {
            int sharedSymmetry = rc.readSharedArray(SHARED_SYMMETRY_INDEX);
            if (sharedSymmetry != 0) {
                canFlipX = sharedSymmetry == Symmetry.FLIP_X.ordinal();
                canFlipY = sharedSymmetry == Symmetry.FLIP_Y.ordinal();
                canRotate = sharedSymmetry == Symmetry.ROTATE.ordinal();
                updateSymmetry();
            }
        }

        sharedArrayMineCount = rc.readSharedArray(SHARED_MINE_COUNT_INDEX);

        if (sharedArrayMineCount != prevNumberOfMines || symmetry != prevSymmetry) {
            for (int i = 0; i < sharedArrayMineCount; i++) {
                char encodedMine = (char) rc.readSharedArray(SHARED_MINES_START_INDEX + i);
                if (encodedMine != 0) {
                    mineLocations.add(encodedMine);
                    existingMineLocs.add(encodedMine);
                    if (symmetry != Symmetry.UNKNOWN) {
                        MapLocation mineLoc = decodeLocation(encodedMine);
                        MapLocation symLoc = getSymmetricLocation(mineLoc, symmetry);
                        char symmetricalEncoded = encodeLocation(symLoc);
                        mineLocations.add(symmetricalEncoded);
                        existingMineLocs.add(symmetricalEncoded);
                    }
                }

            }
            prevNumberOfMines = sharedArrayMineCount;
            prevSymmetry = symmetry;
        }

        for (int i = 0; i < 5; i++) {
            int encodedPos = rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + i);
            currentKingLocs[i] = (encodedPos == 0) ? null : Comms.decodeLocation(encodedPos);
        }
    }

    static MapLocation readStartingKingPosition() throws GameActionException {
        return Comms.decodeLocation(rc.readSharedArray(SHARED_STARTING_KING_POSITION_INDEX));
    }

    static void writeStartingKingPosition() throws GameActionException {
        int encodedPos = Comms.encodeLocation(myLoc);

        // Write starting position (encoded) - only done once at init by first king
        if (rc.readSharedArray(SHARED_STARTING_KING_POSITION_INDEX) == 0) {
            rc.writeSharedArray(SHARED_STARTING_KING_POSITION_INDEX, encodedPos);
        }

        myKingIndex = Comms.getKingIndex();

        if (myKingIndex < 5) {
            rc.writeSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + myKingIndex,
                    encodedPos);
        }
    }

    static MapLocation readCurrentKingPosition(int kingIndex) throws GameActionException {
        int encodedPos = rc.readSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + kingIndex);
        if (encodedPos == 0) {
            return null;
        }
        return Comms.decodeLocation(encodedPos);
    }

    static void writeCurrentKingPosition() throws GameActionException {
        if (myKingIndex < 5) {
            int encodedCurrentPos = Comms.encodeLocation(myLoc);
            rc.writeSharedArray(SHARED_CURRENT_KING_POSITION_INDEX + myKingIndex,
                    encodedCurrentPos);
        }
    }

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

    // --- FORM RAT KING LOCATION METHODS ---

    /**
     * Read the location where rats should gather to form a new rat king.
     * Returns null if no location is set (value is 0).
     */
    static MapLocation readFormRatKingLoc() throws GameActionException {
        int encoded = rc.readSharedArray(SHARED_FORM_RAT_KING_LOC_INDEX);
        if (encoded == 0) {
            return null;
        }
        return decodeLocation(encoded);
    }

    /**
     * Write a location where rats should gather to form a new rat king.
     * Only called by rat king when conditions are met.
     */
    static void writeFormRatKingLoc(MapLocation loc) throws GameActionException {
        if (loc == null) {
            rc.writeSharedArray(SHARED_FORM_RAT_KING_LOC_INDEX, 0);
        } else {
            rc.writeSharedArray(SHARED_FORM_RAT_KING_LOC_INDEX, encodeLocation(loc));
        }
    }

    /**
     * Clear the form rat king location (set back to 0).
     * Called when a new rat king has been formed.
     */
    static void clearFormRatKingLoc() throws GameActionException {
        rc.writeSharedArray(SHARED_FORM_RAT_KING_LOC_INDEX, 0);
    }
}
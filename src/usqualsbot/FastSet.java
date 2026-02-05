// Open source from https://github.com/mhahn2003/bc25/blob/main/java/src/finals/FastSet.java

package usqualsbot;

import battlecode.common.MapLocation;

import static usqualsbot.Comms.*;

import java.util.Iterator;

public class FastSet implements Iterable<Character> {
    private StringBuilder values;

    public FastSet() {
        values = new StringBuilder();
    }

    @Override
    public Iterator<Character> iterator() {
        return new Iterator<Character>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < values.length();
            }

            @Override
            public Character next() {
                return values.charAt(index++);
            }

            @Override
            public void remove() {
                values.deleteCharAt(--index);
            }
        };
    }

    public boolean add(char value) {
        String str = String.valueOf(value);
        if (values.indexOf(str) == -1) {
            values.append(str);
            return true;
        }

        return false;
    }

    public boolean contains(char value) {
        return values.indexOf(String.valueOf(value)) > -1;
    }

    public boolean add(MapLocation location) {
        return add(encodeLocation(location));
    }

    public boolean remove(MapLocation location) {
        char encodedLocation = encodeLocation(location);
        int index = values.indexOf(String.valueOf(encodedLocation));
        if (index != -1) {
            values.deleteCharAt(index);
            return true;
        }
        return false;
    }

    public void clear() {
        values.setLength(0);
    }

    public boolean contains(MapLocation location) {
        return contains(encodeLocation(location));
    }

    public char[] getValues() {
        return values.toString().toCharArray();
    }

    // Method to get all MapLocations
    public MapLocation[] getLocations() {
        char[] chars = getValues();
        MapLocation[] locations = new MapLocation[chars.length];
        for (int i = 0; i < chars.length; i++) {
            locations[i] = decodeLocation(chars[i]);
        }
        return locations;
    }

    public boolean isEmpty() {
        return values.length() == 0;
    }

    public int size() {
        return values.length();
    }

    public boolean addAll(MapLocation[] locations) {
        boolean changed = false;
        for (MapLocation loc : locations) {
            if (add(loc)) {
                changed = true;
            }
        }
        return changed;
    }

    public boolean addAll(FastSet other) {
        boolean changed = false;
        for (char c : other.getValues()) {
            if (add(c)) {
                changed = true;
            }
        }
        return changed;
    }
}
# Overview

Competed in Battlecode 2026, placing top 6 at US Qualifiers and top 12 at Finals. 

All bot code can be found under `/src`. Battlecode has 4 main tournaments, including two sprint tournaments, the qualifier tournament, and the final tournament. My bots initially had fun food-themed names (I competed as team food), but I renamed the files for clarity.


| Name | Description |
|---|---|
| examplefuncsplayer | Starter bot provided by the organizers that randomly moves around. |
| rushbot | My first bot. Prioritizes rushing the 3 possible enemy king starting locations. |
| sprint1bot | Less aggressive; more focus on collecting cheese and natural exploration. |
| benchbot | Minor bugfixes. Used as a benchmark for all future bots. |
| sprint2bot |Complete rewrite with cleaner code and a heuristic-based combat system. |
| usqualsbot | Lots of bug fixes, fine-tuned behaviors, and new features. |
| finalsbot | Same as above. |

You can find a mix of custom and official maps under `/maps`. 

# Battlecode 2026 Scaffold - Java

This is the Battlecode 2026 Java scaffold, containing an `examplefuncsplayer`. Read https://play.battlecode.org/bc26/quick_start !

### Project Structure

- `README.md`
    This file.
- `build.gradle`
    The Gradle build file used to build and run players.
- `src/`
    Player source code.
- `test/`
    Player test code.
- `client/`
    Contains the client. The proper executable can be found in this folder (don't move this!)
- `build/`
    Contains compiled player code and other artifacts of the build process. Can be safely ignored.
- `matches/`
    The output folder for match files.
- `maps/`
    The default folder for custom maps.
- `gradlew`, `gradlew.bat`
    The Unix (OS X/Linux) and Windows versions, respectively, of the Gradle wrapper. These are nifty scripts that you can execute in a terminal to run the Gradle build tasks of this project. If you aren't planning to do command line development, these can be safely ignored.
- `gradle/`
    Contains files used by the Gradle wrapper scripts. Can be safely ignored.

### How to get started

You are free to directly edit `examplefuncsplayer`.
However, we recommend you make a new bot by copying `examplefuncsplayer` to a new package under the `src` folder.

### Useful Commands

- `./gradlew build`
    Compiles your player
- `./gradlew run`
    Runs a game with the settings in gradle.properties
- `./gradlew update`
    Update configurations for the latest version -- run this often
- `./gradlew zipForSubmit`
    Create a submittable zip file
- `./gradlew tasks`
    See what else you can do!


### Configuration 

Look at `gradle.properties` for project-wide configuration.

If you are having any problems with the default client, please report to teh devs and
feel free to set the `compatibilityClient` configuration to `true` to download a different version of the client.

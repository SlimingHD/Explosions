# Large-Scale Voxel Explosion Optimization

This repository contains the Minecraft Forge mod developed for the bachelor's thesis **Optimizing Large-Scale Real-Time Voxel Explosion Simulations**. It provides multiple implementations of a resistance-dependent, ray-based voxel explosion model for comparing execution time, estimated memory allocations, and destruction behavior.

The project is an experimental research prototype rather than a general-purpose explosion mod. **Explosions permanently modify the world. Use a disposable test world and back up any important saves.**

## Requirements

- Minecraft Java Edition **1.20.1**
- Minecraft Forge **47.2.0** (version used for the thesis experiments)
- A Java Development Kit compatible with the repository's Forge Gradle configuration (typically **Java 17** for Minecraft 1.20.1)
- The included Gradle wrapper to build from source

The thesis benchmarks were performed on an Intel Core i7-11700K with 64 GiB of RAM and a maximum JVM heap size of 32 GiB. These specifications describe the measured environment, not minimum requirements.

## Building and running

Clone the repository and run the included Gradle wrapper from its root directory:

```bash
# Windows
.\gradlew.bat build

# Linux / macOS
./gradlew build
```

The built mod JAR is normally written to `build/libs/`. Alternatively, import the Gradle project into an IDE and use its Forge development run configuration. The repository's Gradle configuration determines the exact build and run tasks.

To use a released binary, install the matching Minecraft 1.20.1 Forge version and place the mod JAR in the instance's `mods` directory. Run the commands below in a world where you have permission to execute commands (for example, with cheats enabled).

## Running an individual explosion

```text
/explode <x> <y> <z> <type> <radius> <randomVectorLength>
```

- `x y z`: Explosion center as a Minecraft block position.
- `type`: One of the implementation identifiers listed below.
- `radius`: Integer from **0** to **1000**.
- `randomVectorLength`: Floating-point value from **0.0** to **1.0**, controlling random variation in initial ray length.

Example, using the final multithreaded implementation at the thesis benchmark's Y-coordinate:

```text
/explode 0 141 0 MEMORY_REDUCED_MULTITHREADED 400 0.05
```

The explosion's profiler results are printed by the implementation when it finishes. Large radii can take substantial time and require additional chunks to be generated or loaded.

## Running an automated experiment

```text
/explode-experiment <x> <y> <z> <type> <initCount> <testCount> <waitTime> <sizeList>
```

- `initCount`: Number of warm-up runs per radius (**1–5**).
- `testCount`: Number of recorded runs per radius (**5–50**).
- `waitTime`: Nonnegative wait time, in **seconds**, after each recorded explosion; choose sufficient time for the intended world-loading conditions.
- `sizeList`: `REDUCED_TEST_SIZES` (**50, 100, 200, 400**) or `TEST_SIZES` (**50, 100, 200, 400, 800**).

Example:

```text
/explode-experiment 0 141 0 MEMORY_REDUCED_MULTITHREADED 3 10 30 TEST_SIZES
```

This command executes the experiment asynchronously and schedules individual explosions on the server. For each radius, it performs the specified number of warm-up runs (excluded from the results) and then the measured runs. Between radius groups, the command waits an additional **30 seconds** after moving the player. The test positions advance along the positive X-axis by approximately `2.2 × radius` blocks after each recorded explosion. The warm-up runs for a given radius use the initial position of that radius group; the experiment command does not relocate between individual warm-up runs.

**Important:** The command modifies a large number of blocks, moves the executing player, and may trigger chunk generation. Run one experiment at a time, as its result collection uses shared global state. Do not rely on a default wait time to guarantee that chunks are loaded before every explosion.

Upon completion, the aggregated profiler output is printed to the server console and written to:

```text
<server-directory>/explosion/result_<TYPE>.txt
```

The filename depends on the implementation type. **Running a new experiment of the same type overwrites its previous result file.** Preserve important measurements before repeating the command.

## Available implementations

The identifiers below correspond to the `ExplosionType` enum used by the commands. `MULTITHREADED` denotes a parallel implementation.

| Thesis stage | Command type(s) |
| --- | --- |
| Original cube baseline | `SIMPLE_CUBE` |
| Alternative emission shapes | `SIMPLE_HALF_CUBE`, `SIMPLE_SPHERE` |
| Section-based caching and marking | `IMPROVED`, `IMPROVED_MULTITHREADED` |
| Reduced spherical ray radius | `IMPROVED_RADIUS_REDUCED`, `IMPROVED_RADIUS_REDUCED_MULTITHREADED` |
| Vertical ray clipping | `IMPROVED_Y_LIMIT_REDUCED`, `IMPROVED_Y_LIMIT_REDUCED_MULTITHREADED` |
| Combined ray reductions | `IMPROVED_FULLY_REDUCED`, `IMPROVED_FULLY_REDUCED_MULTITHREADED` |
| DDA traversal | `DDA`, `DDA_MULTITHREADED` |
| Directly indexed section storage | `MAP_UNWRAPPED`, `MAP_UNWRAPPED_MULTITHREADED` |
| Alternative parallel section management | `SEMI_UNWRAPPED_MULTITHREADED` |
| Palette-compressed resistance storage (final) | `MEMORY_REDUCED`, `MEMORY_REDUCED_MULTITHREADED` |
| Additional implementations not evaluated in the thesis comparison | `MORTON`, `MORTON_MULTITHREADED` |

The thesis rejects the Half Cube and reduced-radius approaches on visual-quality grounds; they are retained in the source for inspection and comparison.

## Reproducing the thesis benchmark conditions

The reported measurements used:

- A Superflat world with **one bedrock, 200 stone, two dirt, and one grass layer**, followed by air.
- Explosion centers at **Y = 141**.
- Random ray-length variation **0.05**; the implementations initialize their random source with seed **0**.
- **Three warm-up runs** and **ten measured runs** per implementation and radius.
- Chunk view distance **32**, with world generation allowed to progress before testing.
- A new identical world for each explosion type and its corresponding experiment run.

The automatic experiment command reproduces the requested counts and radii, but benchmark reproducibility also depends on the world configuration, chunk availability, and system load. The reported allocation figures in the thesis are **manual estimates derived from data structures**, not direct JVM heap or garbage-collection measurements. Timing results from a different computer or world should not be expected to match exactly.

## Limitations

- Very large explosions can be slow and can create or load substantial world data.
- Result files are generated server-side. The command is intended for controlled tests, not concurrent benchmark sessions.
- The prototype may behave differently in heterogeneous worlds than in the thesis's Superflat benchmark environment.
- The thesis reports that the implementation does not guarantee completion within one frame or server tick; asynchronous computation does not itself guarantee uninterrupted gameplay responsiveness.

## Further information

The accompanying bachelor's thesis provides the mathematical model, implementation changes, experimental setup, results, and discussion of limitations. For a stable reproduction reference, use the release tag or commit associated with the thesis submission.

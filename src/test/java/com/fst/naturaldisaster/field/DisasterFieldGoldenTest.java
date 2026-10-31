package com.fst.naturaldisaster.field;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bit-exact parity check of the pure field against a fixture.
 *
 * <p>The fixture records the deterministic field outputs: center positions, chunk coverage and the
 * lifecycle-aware event table (phase, effect gate, motion-frame radius). It is the authority for
 * this check; event and coverage expectations were regenerated for the lifecycle contract instead
 * of being copied from an older implementation.
 *
 * <p>No Minecraft classes are touched; this test only uses the pure field package and the fixture in
 * {@code src/test/resources/field/}.
 */
class DisasterFieldGoldenTest {
    private static final String FIXTURE_RESOURCE = "/field/disaster-field-golden-v1.txt";
    private static final int SUPPORTED_FIXTURE_VERSION = 1;

    @Test
    void fieldMatchesGoldenFixtureBitForBit() {
        Fixture fixture = loadFixture();
        assertMatrixCoverage(fixture);
        for (GoldenCase goldenCase : fixture.cases) {
            verifyCase(fixture, goldenCase);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------------------------------

    private static void verifyCase(Fixture fixture, GoldenCase goldenCase) {
        FieldParams params = fixture.params.get(goldenCase.paramsId);
        assertNotNull(params, "case " + goldenCase.id + " references unknown params " + goldenCase.paramsId);

        String context = "case " + goldenCase.id;
        assertEquals(goldenCase.dimensionHash, DisasterField.dimensionHash(goldenCase.dimension),
                context + ": dimensionHash for " + goldenCase.dimension);

        DisasterField field = new DisasterField(goldenCase.seed, goldenCase.dimensionHash, params);
        assertEquals(goldenCase.motionStep, field.motionStep(goldenCase.tick),
                context + ": motionStep at tick " + goldenCase.tick);
        assertEquals(params, field.params(), context + ": field params snapshot");

        for (Position expected : goldenCase.positions) {
            verifyPosition(field, goldenCase, expected);
        }

        // A fresh field queried in the opposite order must still produce identical bits: the
        // per-step cache may only change cost, never results.
        DisasterField reversed = new DisasterField(goldenCase.seed, goldenCase.dimensionHash, params);
        List<Position> reversedPositions = new ArrayList<>(goldenCase.positions);
        Collections.reverse(reversedPositions);
        for (Position expected : reversedPositions) {
            verifyPosition(reversed, goldenCase, expected);
        }

        for (Chunk expected : goldenCase.chunks) {
            List<CenterHit> actual = field.centersAffectingChunk(goldenCase.tick, expected.cx, expected.cz);
            String chunkContext = context + ": chunk (" + expected.cx + "," + expected.cz + ")";
            assertEquals(expected.hits.size(), actual.size(), chunkContext + ": hit count");
            for (int index = 0; index < actual.size(); index++) {
                Hit expectedHit = expected.hits.get(index);
                CenterHit actualHit = actual.get(index);
                String hitContext = chunkContext + " hit[" + index + "]";
                assertEquals(expectedHit.gridX, actualHit.gridX(), hitContext + ": gridX");
                assertEquals(expectedHit.gridZ, actualHit.gridZ(), hitContext + ": gridZ");
                assertSameBits(expectedHit.x, actualHit.x(), hitContext + ": x");
                assertSameBits(expectedHit.z, actualHit.z(), hitContext + ": z");
                assertEquals(expectedHit.radiusChunks, actualHit.radiusChunks(),
                        hitContext + ": radiusChunks");
            }
        }

        for (Event expected : goldenCase.events) {
            EventState actual = field.event(goldenCase.tick, expected.i, expected.j);
            String eventContext = context + ": event (" + expected.i + "," + expected.j + ")";
            assertEquals(expected.active, actual.active(), eventContext + ": active");
            assertEquals(expected.startTick, actual.startTick(), eventContext + ": startTick");
            assertEquals(expected.elapsed, actual.elapsed(), eventContext + ": elapsed");
            assertEquals(expected.remaining, actual.remaining(), eventContext + ": remaining");
            assertEquals(expected.phase, actual.phase(), eventContext + ": phase");
            assertEquals(expected.effectActive, actual.effectActive(), eventContext + ": effectActive");
            assertEquals(expected.radiusChunks, actual.radiusChunks(), eventContext + ": radiusChunks");
        }
    }

    private static void verifyPosition(DisasterField field, GoldenCase goldenCase, Position expected) {
        CenterPosition actual = field.centerPosition(goldenCase.tick, expected.i, expected.j);
        String context = "case " + goldenCase.id + ": center (" + expected.i + "," + expected.j + ")";
        assertSameBits(expected.x, actual.x(), context + ": x");
        assertSameBits(expected.z, actual.z(), context + ": z");
    }

    private static void assertSameBits(double expected, double actual, String message) {
        long expectedBits = Double.doubleToRawLongBits(expected);
        long actualBits = Double.doubleToRawLongBits(actual);
        if (expectedBits != actualBits) {
            fail(message + ": expected raw bits 0x" + hex(expectedBits) + " but was 0x" + hex(actualBits));
        }
    }

    private static String hex(long bits) {
        return String.format("%016x", bits);
    }

    // ---------------------------------------------------------------------------------------------
    // Matrix coverage: the fixture itself must keep covering the migration plan's cases.
    // ---------------------------------------------------------------------------------------------

    private static void assertMatrixCoverage(Fixture fixture) {
        assertFalse(fixture.cases.isEmpty(), "fixture contains no cases");

        assertTrue(fixture.params.size() >= 4,
                "fixture must define at least 4 param sets, has " + fixture.params.size());
        assertTrue(fixture.params.values().stream()
                        .anyMatch(p -> p.centersPerKm2() == 1 && p.layers() == 2),
                "missing default-density dual-layer params");
        assertTrue(fixture.params.values().stream()
                        .anyMatch(p -> p.centersPerKm2() == 16 && p.layers() == 2),
                "missing N=16 dual-layer params");
        assertTrue(fixture.params.values().stream()
                        .anyMatch(p -> p.centersPerKm2() == 16 && p.layers() == 1),
                "missing N=16 layers=1 params");
        assertTrue(fixture.params.values().stream()
                        .mapToInt(FieldParams::disasterDurationTicks).distinct().count() >= 2,
                "fixture must cover different disaster durations");
        assertTrue(fixture.params.values().stream()
                        .mapToInt(FieldParams::disasterRadiusChunks).distinct().count() >= 2,
                "fixture must cover different disaster radii");

        Set<Long> seeds = new HashSet<>();
        Set<String> dimensions = new HashSet<>();
        Set<Long> ticks = new HashSet<>();
        for (GoldenCase goldenCase : fixture.cases) {
            seeds.add(goldenCase.seed);
            dimensions.add(goldenCase.dimension);
            ticks.add(goldenCase.tick);
        }
        assertTrue(seeds.containsAll(Set.of(0L, -1L, Long.MIN_VALUE)),
                "fixture must cover seeds 0, -1 and Long.MIN_VALUE, has " + seeds);
        assertTrue(seeds.stream().anyMatch(s -> s != 0L && s != -1L && s != Long.MIN_VALUE),
                "fixture must cover a regular non-zero seed, has " + seeds);
        assertTrue(dimensions.containsAll(Set.of("minecraft:overworld", "minecraft:the_nether")),
                "fixture must cover overworld and nether, has " + dimensions);
        assertTrue(dimensions.stream().anyMatch(d -> !d.startsWith("minecraft:")),
                "fixture must cover a custom dimension, has " + dimensions);
        assertTrue(ticks.containsAll(Set.of(-401L, -200L, -1L, 0L, 1L, 199L, 200L, 201L)),
                "fixture must cover step boundaries and negative ticks, has " + ticks);
        assertTrue(ticks.stream().anyMatch(t -> t > 1000L),
                "fixture must cover a large tick, has " + ticks);

        boolean largeCoordinates = fixture.cases.stream()
                .flatMap(c -> c.positions.stream())
                .anyMatch(p -> Math.abs((long) p.i) >= 30000L && Math.abs(p.x) >= 2.9e7);
        assertTrue(largeCoordinates, "fixture must cover grid coordinates near +/-3e7 blocks");

        assertTrue(fixture.cases.stream().flatMap(c -> c.chunks.stream())
                        .anyMatch(ch -> ch.cx == 0 && ch.cz == 0),
                "fixture must cover the origin chunk");
        assertTrue(fixture.cases.stream().flatMap(c -> c.chunks.stream())
                        .anyMatch(ch -> ch.cx >= 1_875_000),
                "fixture must cover a chunk near +3e7 blocks");
        assertTrue(fixture.cases.stream().flatMap(c -> c.chunks.stream())
                        .anyMatch(ch -> ch.cx <= -1_875_000),
                "fixture must cover a chunk near -3e7 blocks");

        boolean emptyChunk = fixture.cases.stream()
                .flatMap(c -> c.chunks.stream())
                .anyMatch(ch -> ch.hits.isEmpty());
        assertTrue(emptyChunk, "fixture must cover a chunk with no covering center");

        boolean multiHitChunk = fixture.cases.stream()
                .flatMap(c -> c.chunks.stream())
                .anyMatch(ch -> ch.hits.size() >= 2);
        assertTrue(multiHitChunk, "fixture must cover a chunk with several covering centers");

        boolean activeAtStart = false;
        boolean activeAtLastTick = false;
        Set<EventState.Phase> phases = new HashSet<>();
        for (GoldenCase goldenCase : fixture.cases) {
            int duration = fixture.params.get(goldenCase.paramsId).disasterDurationTicks();
            for (Event event : goldenCase.events) {
                if (event.active && event.elapsed == 0L && event.remaining == duration) {
                    activeAtStart = true;
                }
                if (event.active && event.remaining == 1L) {
                    activeAtLastTick = true;
                }
                if (event.active) {
                    phases.add(event.phase);
                    assertEquals(EventState.Phase.WARNING != event.phase, event.effectActive,
                            "fixture effect gate must match phase");
                }
            }
        }
        assertTrue(activeAtStart, "fixture must cover an event's first tick");
        assertTrue(activeAtLastTick, "fixture must cover an event's last tick");
        assertTrue(phases.containsAll(Set.of(EventState.Phase.WARNING,
                        EventState.Phase.GROWING, EventState.Phase.DECAYING)),
                "fixture must cover warning, growing and decaying phases, has " + phases);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture loading and parsing
    // ---------------------------------------------------------------------------------------------

    private static Fixture loadFixture() {
        InputStream stream = DisasterFieldGoldenTest.class.getResourceAsStream(FIXTURE_RESOURCE);
        if (stream == null) {
            fail("golden fixture resource is missing: " + FIXTURE_RESOURCE);
            return null;
        }
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        } catch (IOException e) {
            fail("failed to read golden fixture " + FIXTURE_RESOURCE, e);
            return null;
        }
        return parseFixture(lines);
    }

    private static Fixture parseFixture(List<String> lines) {
        Map<String, FieldParams> params = new LinkedHashMap<>();
        List<GoldenCase> cases = new ArrayList<>();
        Integer version = null;
        GoldenCase currentCase = null;
        Chunk currentChunk = null;
        boolean seenCase = false;

        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            String record = tokens[0];

            if (record.equals("fixture-version")) {
                if (tokens.length != 2) {
                    throw parseError(lineNumber, "fixture-version takes exactly one value");
                }
                if (version != null) {
                    throw parseError(lineNumber, "duplicate fixture-version");
                }
                int parsed;
                try {
                    parsed = Integer.parseInt(tokens[1]);
                } catch (NumberFormatException e) {
                    throw parseError(lineNumber, "fixture-version is not an integer: " + tokens[1]);
                }
                if (parsed != SUPPORTED_FIXTURE_VERSION) {
                    throw parseError(lineNumber, "unknown fixture version " + parsed
                            + ", supported: " + SUPPORTED_FIXTURE_VERSION);
                }
                version = parsed;
                continue;
            }

            Map<String, String> attributes = attributes(tokens, lineNumber);

            switch (record) {
                case "params" -> {
                    requireKeys(attributes, lineNumber, "id", "centersPerKm2", "kappa", "layers",
                            "coarseCells", "coarseKappa", "disasterDurationTicks", "disasterRadiusChunks",
                            "walkSpeedBlocksPerTick");
                    if (version == null) {
                        throw parseError(lineNumber, "params before fixture-version");
                    }
                    if (seenCase) {
                        throw parseError(lineNumber, "params after the first case");
                    }
                    String id = attributes.get("id");
                    if (params.containsKey(id)) {
                        throw parseError(lineNumber, "duplicate params id " + id);
                    }
                    try {
                        params.put(id, new FieldParams(
                                parseInt(attributes, "centersPerKm2", lineNumber),
                                parseDoubleBits(attributes, "kappa", lineNumber),
                                parseInt(attributes, "layers", lineNumber),
                                parseInt(attributes, "coarseCells", lineNumber),
                                parseDoubleBits(attributes, "coarseKappa", lineNumber),
                                parseInt(attributes, "disasterDurationTicks", lineNumber),
                                parseInt(attributes, "disasterRadiusChunks", lineNumber),
                                parseDoubleBits(attributes, "walkSpeedBlocksPerTick", lineNumber)));
                    } catch (IllegalArgumentException e) {
                        throw parseError(lineNumber, "invalid FieldParams: " + e.getMessage());
                    }
                }
                case "case" -> {
                    requireKeys(attributes, lineNumber, "id", "params", "seed", "dimension",
                            "dimensionHash", "tick", "motionStep");
                    if (version == null) {
                        throw parseError(lineNumber, "case before fixture-version");
                    }
                    closeChunk(currentChunk, lineNumber);
                    currentChunk = null;
                    String paramsId = attributes.get("params");
                    if (!params.containsKey(paramsId)) {
                        throw parseError(lineNumber, "case references unknown params " + paramsId);
                    }
                    GoldenCase newCase = new GoldenCase(
                            attributes.get("id"),
                            paramsId,
                            parseLong(attributes, "seed", lineNumber),
                            attributes.get("dimension"),
                            parseInt(attributes, "dimensionHash", lineNumber),
                            parseLong(attributes, "tick", lineNumber),
                            parseLong(attributes, "motionStep", lineNumber));
                    if (cases.stream().anyMatch(c -> c.id.equals(newCase.id))) {
                        throw parseError(lineNumber, "duplicate case id " + newCase.id);
                    }
                    cases.add(newCase);
                    currentCase = newCase;
                    seenCase = true;
                }
                case "center" -> {
                    requireKeys(attributes, lineNumber, "i", "j", "x", "z");
                    GoldenCase target = requireCase(currentCase, lineNumber);
                    target.positions.add(new Position(
                            parseInt(attributes, "i", lineNumber),
                            parseInt(attributes, "j", lineNumber),
                            parseDoubleBits(attributes, "x", lineNumber),
                            parseDoubleBits(attributes, "z", lineNumber)));
                }
                case "chunk" -> {
                    requireKeys(attributes, lineNumber, "cx", "cz", "hits");
                    GoldenCase target = requireCase(currentCase, lineNumber);
                    closeChunk(currentChunk, lineNumber);
                    currentChunk = new Chunk(
                            parseInt(attributes, "cx", lineNumber),
                            parseInt(attributes, "cz", lineNumber),
                            parseInt(attributes, "hits", lineNumber));
                    target.chunks.add(currentChunk);
                }
                case "hit" -> {
                    requireKeys(attributes, lineNumber, "gridX", "gridZ", "x", "z", "radiusChunks");
                    if (currentChunk == null) {
                        throw parseError(lineNumber, "hit outside a chunk block");
                    }
                    currentChunk.hits.add(new Hit(
                            parseInt(attributes, "gridX", lineNumber),
                            parseInt(attributes, "gridZ", lineNumber),
                            parseDoubleBits(attributes, "x", lineNumber),
                            parseDoubleBits(attributes, "z", lineNumber),
                            parseInt(attributes, "radiusChunks", lineNumber)));
                }
                case "event" -> {
                    requireKeys(attributes, lineNumber, "i", "j", "active", "startTick", "elapsed",
                            "remaining", "phase", "effectActive", "radiusChunks");
                    GoldenCase target = requireCase(currentCase, lineNumber);
                    target.events.add(new Event(
                            parseInt(attributes, "i", lineNumber),
                            parseInt(attributes, "j", lineNumber),
                            parseBoolean(attributes, "active", lineNumber),
                            parseLong(attributes, "startTick", lineNumber),
                            parseLong(attributes, "elapsed", lineNumber),
                            parseLong(attributes, "remaining", lineNumber),
                            parsePhase(attributes, "phase", lineNumber),
                            parseBoolean(attributes, "effectActive", lineNumber),
                            parseInt(attributes, "radiusChunks", lineNumber)));
                }
                default -> throw parseError(lineNumber, "unknown record type " + record);
            }
        }
        closeChunk(currentChunk, lines.size());

        if (version == null) {
            throw new AssertionError("golden fixture is empty or missing the fixture-version header");
        }
        if (cases.isEmpty()) {
            throw new AssertionError("golden fixture contains no cases");
        }
        return new Fixture(params, List.copyOf(cases));
    }

    private static void closeChunk(Chunk chunk, int lineNumber) {
        if (chunk == null) {
            return;
        }
        if (chunk.hits.size() != chunk.declaredHits) {
            throw parseError(lineNumber, "chunk (" + chunk.cx + "," + chunk.cz + ") declares "
                    + chunk.declaredHits + " hits but lists " + chunk.hits.size());
        }
    }

    private static GoldenCase requireCase(GoldenCase currentCase, int lineNumber) {
        if (currentCase == null) {
            throw parseError(lineNumber, "query record before the first case");
        }
        return currentCase;
    }

    private static Map<String, String> attributes(String[] tokens, int lineNumber) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int tokenIndex = 1; tokenIndex < tokens.length; tokenIndex++) {
            String token = tokens[tokenIndex];
            int separator = token.indexOf('=');
            if (separator <= 0 || separator == token.length() - 1) {
                throw parseError(lineNumber, "malformed attribute '" + token + "'");
            }
            String key = token.substring(0, separator);
            if (attributes.put(key, token.substring(separator + 1)) != null) {
                throw parseError(lineNumber, "duplicate attribute " + key);
            }
        }
        return attributes;
    }

    private static void requireKeys(Map<String, String> attributes, int lineNumber, String... required) {
        Set<String> expected = Set.of(required);
        if (!attributes.keySet().equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(attributes.keySet());
            Set<String> unexpected = new HashSet<>(attributes.keySet());
            unexpected.removeAll(expected);
            throw parseError(lineNumber, "attribute mismatch, missing=" + missing
                    + " unexpected=" + unexpected);
        }
    }

    private static int parseInt(Map<String, String> attributes, String key, int lineNumber) {
        try {
            return Integer.parseInt(attributes.get(key));
        } catch (NumberFormatException e) {
            throw parseError(lineNumber, key + " is not an int: " + attributes.get(key));
        }
    }

    private static long parseLong(Map<String, String> attributes, String key, int lineNumber) {
        try {
            return Long.parseLong(attributes.get(key));
        } catch (NumberFormatException e) {
            throw parseError(lineNumber, key + " is not a long: " + attributes.get(key));
        }
    }

    private static boolean parseBoolean(Map<String, String> attributes, String key, int lineNumber) {
        return switch (attributes.get(key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw parseError(lineNumber, key + " is not a boolean: " + attributes.get(key));
        };
    }

    private static EventState.Phase parsePhase(Map<String, String> attributes, String key, int lineNumber) {
        try {
            return EventState.Phase.valueOf(attributes.get(key));
        } catch (IllegalArgumentException e) {
            throw parseError(lineNumber, key + " is not a lifecycle phase: " + attributes.get(key));
        }
    }

    private static double parseDoubleBits(Map<String, String> attributes, String key, int lineNumber) {
        String value = attributes.get(key);
        if (!value.startsWith("0x") || value.length() != 18) {
            throw parseError(lineNumber, key + " is not 16-digit raw double bits: " + value);
        }
        try {
            return Double.longBitsToDouble(Long.parseUnsignedLong(value.substring(2), 16));
        } catch (NumberFormatException e) {
            throw parseError(lineNumber, key + " is not hexadecimal: " + value);
        }
    }

    private static AssertionError parseError(int lineNumber, String message) {
        return new AssertionError("golden fixture line " + lineNumber + ": " + message);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture model
    // ---------------------------------------------------------------------------------------------

    private record Fixture(Map<String, FieldParams> params, List<GoldenCase> cases) {
    }

    private static final class GoldenCase {
        final String id;
        final String paramsId;
        final long seed;
        final String dimension;
        final int dimensionHash;
        final long tick;
        final long motionStep;
        final List<Position> positions = new ArrayList<>();
        final List<Chunk> chunks = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        GoldenCase(String id, String paramsId, long seed, String dimension, int dimensionHash,
                   long tick, long motionStep) {
            this.id = id;
            this.paramsId = paramsId;
            this.seed = seed;
            this.dimension = dimension;
            this.dimensionHash = dimensionHash;
            this.tick = tick;
            this.motionStep = motionStep;
        }
    }

    private record Position(int i, int j, double x, double z) {
    }

    private static final class Chunk {
        final int cx;
        final int cz;
        final int declaredHits;
        final List<Hit> hits = new ArrayList<>();

        Chunk(int cx, int cz, int declaredHits) {
            this.cx = cx;
            this.cz = cz;
            this.declaredHits = declaredHits;
        }
    }

    private record Hit(int gridX, int gridZ, double x, double z, int radiusChunks) {
    }

    private record Event(int i, int j, boolean active, long startTick, long elapsed, long remaining,
                         EventState.Phase phase, boolean effectActive, int radiusChunks) {
    }
}

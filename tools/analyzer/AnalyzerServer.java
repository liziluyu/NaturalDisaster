import com.fst.naturaldisaster.field.FieldParams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal local web front end for {@link ChunkSimulation}.
 *
 * <p>Usage: {@code java AnalyzerServer [port] [webRoot/index.html] [--no-open]} (defaults: 8765,
 * tools/analyzer/index.html). The server binds to 127.0.0.1 only and stops when this process is
 * terminated with Ctrl+C.
 */
public final class AnalyzerServer {
    private static final int DEFAULT_PORT = 8765;
    private static final long MAX_TOTAL_TICKS = 200_000_000L;
    private static final int MAX_RADIUS_CHUNKS = 32;
    private static final double MAX_DURATION_SECONDS = 3600.0;
    private static final double DEFAULT_DURATION_SECONDS =
            ChunkSimulation.DEFAULT_DURATION_TICKS / ChunkSimulation.TICKS_PER_SECOND;

    private AnalyzerServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        String webRoot = "tools/analyzer/index.html";
        boolean openBrowser = true;

        int positional = 0;
        for (String arg : args) {
            if ("--no-open".equals(arg)) {
                openBrowser = false;
            } else if (positional == 0 && arg.matches("\\d+")) {
                port = Integer.parseInt(arg);
                positional++;
            } else if (positional <= 1) {
                webRoot = arg;
                positional = 2;
            } else {
                throw new IllegalArgumentException("无法识别的参数: " + arg);
            }
        }

        Path htmlPath = Path.of(webRoot).toAbsolutePath();
        if (!Files.isRegularFile(htmlPath)) {
            System.err.println("找不到页面文件: " + htmlPath);
            System.exit(1);
        }
        byte[] html = Files.readAllBytes(htmlPath);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            System.err.println("无法绑定 127.0.0.1:" + port + "，端口可能已被占用。");
            System.err.println("关闭占用该端口的程序后重试，或删除启动脚本中的服务进程。");
            System.exit(1);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "analyzer-worker");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        server.createContext("/", exchange -> serveHtml(exchange, html));
        server.createContext("/api/simulate", AnalyzerServer::handleSimulate);
        server.createContext("/api/config", AnalyzerServer::handleConfig);

        server.start();
        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("天灾分析页面: " + url);
        System.out.println("按 Ctrl+C 停止服务。");
        if (openBrowser) {
            openBrowser(url);
        }
    }

    private static void serveHtml(HttpExchange exchange, byte[] html) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"/".equals(path) && !"/index.html".equals(path)) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "text/plain; charset=utf-8",
                        "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            send(exchange, 200, "text/html; charset=utf-8", html);
        } finally {
            exchange.close();
        }
    }

    private static void handleSimulate(HttpExchange exchange) throws IOException {
        try {
            ChunkSimulation.Result result = ChunkSimulation.run(parseConfig(readParams(exchange)));
            send(exchange, 200, "application/json; charset=utf-8",
                    toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "模拟失败: " + e);
        } finally {
            exchange.close();
        }
    }

    private static void handleConfig(HttpExchange exchange) throws IOException {
        try {
            ChunkSimulation.Config config = parseConfigFile(readParams(exchange));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"naturaldisaster-common.toml\"");
            send(exchange, 200, "application/toml; charset=utf-8",
                    toConfigToml(config).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "生成配置失败: " + e);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> readParams(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            return parseForm(exchange.getRequestURI().getRawQuery());
        }
        if ("POST".equals(method)) {
            byte[] body = exchange.getRequestBody().readNBytes(65_537);
            if (body.length > 65_536) {
                throw new IllegalArgumentException("请求体过大");
            }
            return parseForm(new String(body, StandardCharsets.UTF_8));
        }
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        throw new IllegalArgumentException("仅支持 GET 或 POST");
    }

    private static ChunkSimulation.Config parseConfig(Map<String, String> params) {
        long seed = parseLong(params, "seed", 0L);
        int chunkX = parseInt(params, "chunkX", 0);
        int chunkZ = parseInt(params, "chunkZ", 0);
        long totalTicks = parseLong(params, "totalTicks", ChunkSimulation.DEFAULT_TOTAL_TICKS);
        int centersPerKm2 = parseInt(params, "centersPerKm2", ChunkSimulation.DEFAULT_CENTERS_PER_KM2);
        double kappa = parseDouble(params, "kappa", ChunkSimulation.DEFAULT_KAPPA);
        int layers = parseInt(params, "layers", ChunkSimulation.DEFAULT_LAYERS);
        double walkSpeed = parseDouble(params, "walkSpeedBlocksPerTick",
                ChunkSimulation.DEFAULT_WALK_SPEED_BLOCKS_PER_TICK);
        double durationSeconds = parseDouble(params, "durationSeconds", DEFAULT_DURATION_SECONDS);
        long radiusInput = parseLong(params, "radiusChunks", ChunkSimulation.DEFAULT_RADIUS_CHUNKS);

        if (totalTicks <= 0L) {
            throw new IllegalArgumentException("模拟总 tick 必须为正数");
        }
        if (totalTicks > MAX_TOTAL_TICKS) {
            throw new IllegalArgumentException("模拟总 tick 过大，最多 " + MAX_TOTAL_TICKS);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            throw new IllegalArgumentException("天灾时长（秒）必须为正数");
        }
        if (durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("天灾时长最多 " + (int) MAX_DURATION_SECONDS + " 秒");
        }
        long durationTicks = Math.round(durationSeconds * ChunkSimulation.TICKS_PER_SECOND);
        if (durationTicks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("天灾时长过大");
        }
        if (radiusInput < 1L || radiusInput > MAX_RADIUS_CHUNKS) {
            throw new IllegalArgumentException("最大半径必须在 1 到 " + MAX_RADIUS_CHUNKS + " 区块之间");
        }
        return new ChunkSimulation.Config(seed, chunkX, chunkZ, totalTicks,
                centersPerKm2, kappa, layers, walkSpeed, (int) durationTicks, (int) radiusInput);
    }

    private static ChunkSimulation.Config parseConfigFile(Map<String, String> params) {
        int centersPerKm2 = parseInt(params, "centersPerKm2", ChunkSimulation.DEFAULT_CENTERS_PER_KM2);
        double kappa = parseDouble(params, "kappa", ChunkSimulation.DEFAULT_KAPPA);
        int layers = parseInt(params, "layers", ChunkSimulation.DEFAULT_LAYERS);
        double walkSpeed = parseDouble(params, "walkSpeedBlocksPerTick",
                ChunkSimulation.DEFAULT_WALK_SPEED_BLOCKS_PER_TICK);
        long durationSeconds = parseLong(params, "durationSeconds",
                (long) DEFAULT_DURATION_SECONDS);
        long radiusInput = parseLong(params, "radiusChunks", ChunkSimulation.DEFAULT_RADIUS_CHUNKS);

        if (durationSeconds < 1L || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("天灾时长（秒）必须为正整数，且最多 "
                    + (int) MAX_DURATION_SECONDS + " 秒");
        }
        if (radiusInput < 1L || radiusInput > MAX_RADIUS_CHUNKS) {
            throw new IllegalArgumentException("最大半径必须在 1 到 " + MAX_RADIUS_CHUNKS + " 区块之间");
        }
        ChunkSimulation.Config config = new ChunkSimulation.Config(
                0L, 0, 0, ChunkSimulation.DEFAULT_TOTAL_TICKS,
                centersPerKm2, kappa, layers, walkSpeed,
                (int) durationSeconds * (int) ChunkSimulation.TICKS_PER_SECOND,
                (int) radiusInput);
        config.validate();
        return config;
    }

    private static long parseLong(Map<String, String> params, String key, long fallback) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 不是有效整数: " + raw);
        }
    }

    private static int parseInt(Map<String, String> params, String key, int fallback) {
        long value = parseLong(params, key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " 超出整数范围");
        }
        return (int) value;
    }

    private static double parseDouble(Map<String, String> params, String key, double fallback) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 不是有效数字: " + raw);
        }
    }

    private static Map<String, String> parseForm(String rawForm) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawForm == null || rawForm.isEmpty()) {
            return result;
        }
        for (String pair : rawForm.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String toConfigToml(ChunkSimulation.Config config) {
        int durationSeconds = config.durationTicks() / (int) ChunkSimulation.TICKS_PER_SECOND;
        StringBuilder toml = new StringBuilder(640);
        toml.append("# Natural Disaster common config generated by the local analyzer.\n");
        toml.append("# Copy to config/naturaldisaster-common.toml and restart the game.\n");
        toml.append("# Only the six field parameters are included; seed, chunk and totalTicks are analyzer-only.\n");
        toml.append("\n[centers]\n");
        toml.append("# Centers per square kilometre; range 1-4096.\n");
        toml.append("centersPerKm2 = ").append(config.centersPerKm2()).append('\n');
        toml.append("# Home-range radius as a multiple of grid spacing; range 0.05-1.15.\n");
        toml.append("kappa = ").append(Double.toString(config.kappa())).append('\n');
        toml.append("# Average roaming speed in blocks per tick; range 0.01-2.0.\n");
        toml.append("walkSpeedBlocksPerTick = ")
                .append(Double.toString(config.walkSpeedBlocksPerTick())).append('\n');
        toml.append("# Roaming layers: 2 is the supported configuration, 1 is for comparison.\n");
        toml.append("layers = ").append(config.layers()).append('\n');
        toml.append("# Disaster duration in seconds, including the fixed 60 s warning stage.\n");
        toml.append("disasterDurationSeconds = ").append(durationSeconds).append('\n');
        toml.append("# Maximum effect radius in chunks; range 1-32.\n");
        toml.append("disasterRadiusChunks = ").append(config.radiusChunks()).append('\n');
        return toml.toString();
    }

    private static String toJson(ChunkSimulation.Result result) {
        ChunkSimulation.Config config = result.config();
        FieldParams params = result.params();
        StringBuilder json = new StringBuilder(768);
        json.append('{');
        number(json, "seed", config.seed()).append(',');
        number(json, "chunkX", config.chunkX()).append(',');
        number(json, "chunkZ", config.chunkZ()).append(',');
        number(json, "totalTicks", config.totalTicks()).append(',');
        decimal(json, "totalSeconds", config.totalTicks() / ChunkSimulation.TICKS_PER_SECOND).append(',');
        number(json, "durationTicks", config.durationTicks()).append(',');
        decimal(json, "durationSeconds", config.durationTicks() / ChunkSimulation.TICKS_PER_SECOND).append(',');
        number(json, "radiusChunks", config.radiusChunks()).append(',');
        number(json, "centersPerKm2", params.centersPerKm2()).append(',');
        decimal(json, "kappa", params.kappa()).append(',');
        number(json, "layers", params.layers()).append(',');
        decimal(json, "walkSpeedBlocksPerTick", params.walkSpeedBlocksPerTick()).append(',');
        number(json, "coarseCells", params.coarseCells()).append(',');
        decimal(json, "coarseKappa", params.coarseKappa()).append(',');
        number(json, "activeTicks", result.activeTicks()).append(',');
        decimal(json, "occurrenceRatioPercent", result.occurrenceRatioPercent()).append(',');
        nullableDecimal(json, "averageDurationTicks", result.averageDurationTicks()).append(',');
        nullableDecimal(json, "averageDurationSeconds",
                ChunkSimulation.Result.toSeconds(result.averageDurationTicks())).append(',');
        nullableNumber(json, "minDurationTicks", result.minDurationTicks()).append(',');
        nullableDecimal(json, "minDurationSeconds",
                secondsOrNull(result.minDurationTicks())).append(',');
        nullableNumber(json, "maxDurationTicks", result.maxDurationTicks()).append(',');
        nullableDecimal(json, "maxDurationSeconds",
                secondsOrNull(result.maxDurationTicks())).append(',');
        nullableDecimal(json, "medianDurationTicks", result.medianDurationTicks()).append(',');
        nullableDecimal(json, "medianDurationSeconds",
                ChunkSimulation.Result.toSeconds(result.medianDurationTicks())).append(',');
        nullableDecimal(json, "averageIdleTicks", result.averageIdleTicks()).append(',');
        nullableDecimal(json, "averageIdleSeconds",
                ChunkSimulation.Result.toSeconds(result.averageIdleTicks())).append(',');
        number(json, "completeRuns", result.completeRuns()).append(',');
        number(json, "completeIntervals", result.completeIntervals()).append(',');
        number(json, "elapsedMillis", result.elapsedMillis());
        json.append('}');
        return json.toString();
    }

    private static StringBuilder number(StringBuilder json, String key, long value) {
        return json.append('"').append(key).append("\":").append(value);
    }

    private static StringBuilder decimal(StringBuilder json, String key, double value) {
        return json.append('"').append(key).append("\":")
                .append(String.format(Locale.ROOT, "%.4f", value));
    }

    private static double secondsOrNull(long ticks) {
        return ticks < 0L ? Double.NaN : ChunkSimulation.Result.toSeconds(ticks);
    }

    private static StringBuilder nullableNumber(StringBuilder json, String key, long value) {
        return json.append('"').append(key).append("\":")
                .append(value < 0L ? "null" : Long.toString(value));
    }

    private static StringBuilder nullableDecimal(StringBuilder json, String key, double value) {
        return json.append('"').append(key).append("\":")
                .append(Double.isNaN(value) ? "null" : String.format(Locale.ROOT, "%.4f", value));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"error\":\"" + escapeJson(message) + "\"}";
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the platform command.
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (IOException e) {
            System.out.println("无法自动打开浏览器，请手动访问 " + url);
        }
    }
}

package dev.ysmbridge;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Mod("ysm_reload_bridge")
public final class ReloadBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private final Path root = FMLPaths.GAMEDIR.get().resolve("config/ysm_upload_bridge");
    private ScheduledExecutorService worker;
    private long nextReload;
    private long nextCleanup;

    public ReloadBridge() {
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }

    private void start(ServerStartedEvent event) {
        nextReload = 0;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ysm-reload-bridge");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> poll(event.getServer()), 5, 5, TimeUnit.SECONDS);
    }

    private void stop(ServerStoppingEvent event) {
        if (worker != null) worker.shutdownNow();
    }

    private void poll(MinecraftServer server) {
        try {
            Files.createDirectories(root.resolve("requests"));
            Files.createDirectories(root.resolve("acks"));
            writeJSON(root.resolve("heartbeat.json"), Map.of("version", "1.0.0", "time", Instant.now().toString()));
            cleanup();
            if (System.currentTimeMillis() < nextReload) return;
            List<Path> batch = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.resolve("requests"), "*.req")) {
                for (Path file : entries) {
                    if (!file.getFileName().toString().matches("[a-f0-9]{32}\\.req")) continue;
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    String id = file.getFileName().toString().substring(0, 32);
                    if (Files.exists(root.resolve("acks/" + id + ".json"))) {
                        Files.deleteIfExists(file);
                        continue;
                    }
                    batch.add(file);
                    if (batch.size() >= 64) break;
                }
            }
            if (batch.isEmpty()) return;
            nextReload = System.currentTimeMillis() + 30_000;
            CompletableFuture<String> command = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    int result = server.getCommands().getDispatcher().execute(
                            "ysm model reload", server.createCommandSourceStack().withPermission(4));
                    command.complete(result > 0 ? "dispatched" : "failed");
                } catch (Exception e) {
                    LOGGER.error("YSM reload command failed", e);
                    command.complete("failed");
                }
            });
            // The worker waits for the server thread. No second command can overlap this batch.
            String status = command.get();
            for (Path file : batch) {
                String id = file.getFileName().toString().substring(0, 32);
                String message = status.equals("dispatched")
                        ? "重载命令已执行，模型加载和客户端同步结果请在游戏内确认"
                        : "重载命令执行失败，请查看服务器日志并由管理员重试";
                writeJSON(root.resolve("acks/" + id + ".json"), Map.of(
                        "id", id, "status", status, "message", message, "time", Instant.now().toString()));
                Files.deleteIfExists(file);
            }
            LOGGER.info("YSM reload batch {} for {} requests", status, batch.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("Cannot process YSM reload requests; pending requests will be retried", e);
        }
    }

    private void writeJSON(Path target, Object value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanup() throws IOException {
        long now = System.currentTimeMillis();
        if (now < nextCleanup) return;
        nextCleanup = now + 86_400_000;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.resolve("acks"), "*.json")) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                if (!name.matches("[a-f0-9]{32}\\.json") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.exists(root.resolve("requests/" + name.substring(0, 32) + ".req"))) continue;
                if (Files.getLastModifiedTime(file).toMillis() < now - 7 * 86_400_000L) Files.deleteIfExists(file);
            }
        }
    }
}

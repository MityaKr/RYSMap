package works.repka.rysmap.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.MapNet;

/**
 * Телепорт с карты (макет {@code 15b}, этап М8): клавиша T над полотном, пункт меню «Телепорт сюда», кнопка в панели
 * точки. Сервер проверяет правило ({@link MapRules#teleport}), паузу (кроме операторов), измерение и границу мира, ищет
 * безопасное место ({@link SafeSpot}) и переносит игрока. Незагруженный чанк сервер загружает (и, если его ещё нет,
 * создаёт) сам: так телепорт работает и в неразведанное место. Отказ уходит клиенту причиной, клиент пишет её строкой
 * сообщения карты.
 */
public final class Teleports {
    /** Когда игрок телепортировался в последний раз, мс. Переживает выход игрока: иначе паузу обходят перезаходом. */
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private Teleports() {}

    static void clear() {
        LAST.clear();
    }

    /** Сколько секунд паузы осталось у игрока (у оператора 0). */
    static int pauseLeft(ServerPlayer player) {
        int pause = MapServer.rules().pause;
        if (pause <= 0 || MapServer.op(player)) return 0;
        Long last = LAST.get(player.getUUID());
        if (last == null) return 0;
        long left = last + pause * 1000L - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /** Игроки, чей телепорт ждёт загрузки чанков: второй запрос до ответа не принимается. */
    private static final java.util.Set<UUID> PENDING = new java.util.HashSet<>();
    /** Сколько телепортов одновременно ждут загрузки на всём сервере. */
    private static final int MAX_PENDING = 4;
    /** Тикет загрузки места телепорта: держит чанки 10 секунд, пока идёт поиск. */
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> TICKET =
        net.minecraft.server.level.TicketType.create("rysmap_teleport", java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong), 200);

    /** Пауза у игрока, который не оператор: и после телепорта, и после отказа на месте (нет места, граница). */
    private static void pause(ServerPlayer player) {
        if (!MapServer.op(player)) LAST.put(player.getUUID(), System.currentTimeMillis());
    }

    /**
     * Измерение разрешено: своё текущее, из списка правила ({@link MapRules#teleportDimensions}) или любое у оператора.
     * Клиент присылает имя сам, поэтому сервер не верит ему на слово (перепроверка 18.11, пункт 2).
     */
    static boolean dimensionAllowed(ServerPlayer player, ServerLevel level, MapRules rules) {
        if (MapServer.op(player) || player.level() == level) return true;
        return rules.teleportDimensions.contains(level.dimension().location().toString());
    }

    public static void request(ServerPlayer player, MapNet.Teleport t) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        MapRules rules = MapServer.rules();
        if (rules.teleport == MapRules.Teleport.OFF) {
            deny(player, "off", 0);
            return;
        }
        if (!MapServer.canTeleport(player)) {
            deny(player, "ops", 0);
            return;
        }
        int left = pauseLeft(player);
        if (left > 0) {
            deny(player, "pause", left);
            return;
        }
        if (player.isSpectator() || !player.isAlive()) {
            deny(player, "state", 0);
            return;
        }
        ResourceLocation loc = ResourceLocation.tryParse(t.dimension());
        ServerLevel level = loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        if (level == null || !dimensionAllowed(player, level, rules)) {
            deny(player, "dimension", 0);
            return;
        }
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(t.x(), 0, t.z()))) {
            deny(player, "border", 0);
            return;
        }
        if (PENDING.contains(player.getUUID()) || PENDING.size() >= MAX_PENDING) {
            deny(player, "busy", 0);
            return;
        }
        // чанки места (столбец и круг поиска) грузятся и при нужде создаются асинхронно, под тикетом; главный поток не ждёт
        int r = SafeSpot.RADIUS;
        java.util.List<java.util.concurrent.CompletableFuture<?>> loads = new java.util.ArrayList<>();
        java.util.List<net.minecraft.world.level.ChunkPos> held = new java.util.ArrayList<>();
        for (int cz = (t.z() - r) >> 4; cz <= (t.z() + r) >> 4; cz++) {
            for (int cx = (t.x() - r) >> 4; cx <= (t.x() + r) >> 4; cx++) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(TICKET, cp, 1, cp);
                held.add(cp);
                loads.add(level.getChunkSource().getChunkFuture(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
            }
        }
        PENDING.add(player.getUUID());
        UUID id = player.getUUID();
        java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0]))
            .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .whenCompleteAsync((v, err) -> {
                PENDING.remove(id);
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                try {
                    if (p == null) return;
                    if (err != null) {
                        pause(p);
                        deny(p, "unsafe", 0);
                        return;
                    }
                    finish(p, level, t, rules);
                } catch (RuntimeException e) {
                    RYSMap.LOG.warn("[rysmap] телепорт {} не прошёл: {}", p == null ? id : p.getGameProfile().getName(), e.toString());
                    if (p != null) deny(p, "unsafe", 0);
                } finally {
                    for (net.minecraft.world.level.ChunkPos cp : held) level.getChunkSource().removeRegionTicket(TICKET, cp, 1, cp);
                }
            }, server);
    }

    /** Чанки загружены: поиск места и перенос. Главный поток. */
    private static void finish(ServerPlayer player, ServerLevel level, MapNet.Teleport t, MapRules rules) {
        if (player.isSpectator() || !player.isAlive()) {
            deny(player, "state", 0);
            return;
        }
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        // под потолком: не выше логической высоты (в Нижнем мире 128), иначе верх мира
        int maxY = ceiling ? Math.min(level.getMaxBuildHeight(), minY + level.dimensionType().logicalHeight()) - 1 : level.getMaxBuildHeight() - 1;
        int hint = t.y();
        if (ceiling && hint == SafeSpot.NO_HINT && player.level() == level) hint = player.getBlockY();
        long t0 = System.nanoTime();
        int[] spot = SafeSpot.find(new LevelProbe(level, ceiling), t.x(), t.z(), minY, maxY, ceiling, hint);
        long micros = (System.nanoTime() - t0) / 1000;
        if (spot == null) {
            RYSMap.LOG.info("[rysmap] телепорт {}: в {} {} {} нет безопасного места ({} мкс)", player.getGameProfile().getName(), t.dimension(), t.x(), t.z(), micros);
            pause(player);
            deny(player, "unsafe", 0);
            return;
        }
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(spot[0], spot[1], spot[2]))) {
            pause(player);
            deny(player, "border", 0);
            return;
        }
        player.teleportTo(level, spot[0] + 0.5, spot[1], spot[2] + 0.5, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        pause(player);
        RYSMap.LOG.info("[rysmap] телепорт {}: {} {} {} {} (поиск {} мкс)", player.getGameProfile().getName(), t.dimension(), spot[0], spot[1], spot[2], micros);
        MapNet.send(player, new MapNet.TeleportResult(true, "", MapServer.op(player) ? 0 : rules.pause));
        MapServer.sendRules(player);
    }

    private static void deny(ServerPlayer player, String reason, int seconds) {
        MapNet.send(player, new MapNet.TeleportResult(false, reason, seconds));
    }

    /** Мир сервера для поиска: чанки уже загружены под тикетом ({@link #request}); чужой чанк вне круга не создаётся. */
    static final class LevelProbe implements SafeSpot.Probe {
        private final ServerLevel level;
        private final boolean ceiling;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        LevelProbe(ServerLevel level, boolean ceiling) {
            this.level = level;
            this.ceiling = ceiling;
        }

        private ChunkAccess chunk(int x, int z) {
            ChunkAccess c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (c == null) throw new IllegalStateException("чанк места телепорта не загружен");
            return c;
        }

        @Override
        public int top(int x, int z) {
            return chunk(x, z).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        }

        @Override
        public SafeSpot.Kind at(int x, int y, int z) {
            if (y < level.getMinBuildHeight()) return SafeSpot.Kind.BLOCKED;
            if (y >= level.getMaxBuildHeight()) return SafeSpot.Kind.FREE;
            pos.set(x, y, z);
            BlockState state = chunk(x, z).getBlockState(pos);
            return kind(level, pos, state, ceiling);
        }
    }

    static SafeSpot.Kind kind(Level level, BlockPos pos, BlockState state, boolean ceiling) {
        var fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return SafeSpot.Kind.DANGER;
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.COBWEB)
            || state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.END_PORTAL) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_GATEWAY))
            return SafeSpot.Kind.DANGER;
        if (!fluid.isEmpty()) return SafeSpot.Kind.FLUID;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return SafeSpot.Kind.FREE;
        // крыша Нижнего мира: бедрок под потолком полом не считается
        if (ceiling && state.is(Blocks.BEDROCK)) return SafeSpot.Kind.BLOCKED;
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0 ? SafeSpot.Kind.FLOOR : SafeSpot.Kind.BLOCKED;
    }
}

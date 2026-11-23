package works.repka.rysmap.server;

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
import net.minecraft.network.chat.Component;
import works.repka.ryscore.travel.TravelApi;
import works.repka.rysmap.net.MapNet;

/**
 * Телепорт с карты (макет {@code 15b}, этап М8): клавиша T над полотном, пункт меню «Телепорт сюда», кнопка в панели
 * точки. Сервер проверяет правило ({@link MapRules#teleport}), паузу (кроме операторов), измерение и границу мира, ищет
 * безопасное место ({@link SafeSpot}) и переносит игрока. Незагруженный чанк сервер загружает (и, если его ещё нет,
 * создаёт) сам: так телепорт работает и в неразведанное место. Отказ уходит клиенту причиной, клиент пишет её строкой
 * сообщения карты.
 */
public final class Teleports {
    private Teleports() {}

    /** Сколько секунд паузы телепорта с карты осталось у игрока (с правом «без паузы» 0). */
    static int pauseLeft(ServerPlayer player) {
        return Travel.INSTANCE.mapLeft(player);
    }

    /**
     * Измерение разрешено: своё текущее, из списка правила ({@link MapRules#teleportDimensions}) или любое у оператора.
     * Клиент присылает имя сам, поэтому сервер не верит ему на слово (перепроверка 18.11, пункт 2).
     */
    static boolean dimensionAllowed(ServerPlayer player, ServerLevel level, MapRules rules) {
        if (MapServer.op(player) || player.level() == level) return true;
        return rules.teleportDimensions.contains(level.dimension().location().toString());
    }

    /** Телепорт с карты (0.2.0-p1): правила, пауза, приват, загрузка и посадка идут через {@link Travel} с причиной MAP. */
    public static void request(ServerPlayer player, MapNet.Teleport t) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ResourceLocation loc = ResourceLocation.tryParse(t.dimension());
        if (loc == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, loc)) == null) {
            deny(player, "dimension", 0);
            return;
        }
        double y = t.y() == SafeSpot.NO_HINT ? SafeSpot.NO_HINT : t.y();
        TravelApi.Trip trip = TravelApi.Trip.to(TravelApi.Spot.of(loc, t.x() + 0.5, y, t.z() + 0.5)).reason(TravelApi.Reason.MAP).build();
        Travel.Res r = Travel.INSTANCE.go(player, trip, true, (ok, why, code) -> {
            if (ok) {
                MapNet.send(player, new MapNet.TeleportResult(true, "", Travel.bypass(player) ? 0 : MapServer.rules().pause));
                MapServer.sendRules(player);
            } else {
                deny(player, code, 0, why);
            }
        });
        if (r.result().status() == TravelApi.Status.PAUSE) deny(player, "pause", r.result().seconds());
        else if (r.result().status() == TravelApi.Status.DENIED) deny(player, r.code(), 0, r.result().message());
    }

    private static void deny(ServerPlayer player, String reason, int seconds) {
        MapNet.send(player, new MapNet.TeleportResult(false, reason, seconds));
    }

    /** Отказ с причиной, которой нет среди кодов клиента 0.1 (приват, разогрев): строка уведомлением мест. */
    private static void deny(ServerPlayer player, String code, int seconds, Component why) {
        if (KNOWN.contains(code)) {
            deny(player, code, seconds);
            return;
        }
        deny(player, "denied", 0);
        PlacesServer.note(player, PlacesServer.Note.DENIED, why, 0, "map");
    }

    /** Коды отказа, у которых есть строка {@code rysmap.tp.<код>} на клиенте 0.1. */
    private static final java.util.Set<String> KNOWN = java.util.Set.of("off", "ops", "pause", "state", "dimension", "border", "unsafe", "busy");

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
            if (c == null) throw new IllegalStateException("teleport place chunk is not loaded");
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
        // пол: верх формы не выше блока (ковёр, снег слоями, нижняя плита, обычный блок); забор и стена выше
        double top = shape.max(Direction.Axis.Y);
        return top > 0 && top <= 1.0 ? SafeSpot.Kind.FLOOR : SafeSpot.Kind.BLOCKED;
    }
}

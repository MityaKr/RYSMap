package works.repka.rysmap.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.team.TeamsApi;
import works.repka.ryscore.travel.TravelApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.places.Cooldowns;
import works.repka.rysmap.places.PlaceBook;
import works.repka.rysmap.places.PlaceRules;

/**
 * Движок перемещений (план Essentials 4.5): всё, что телепортирует игрока в семье, идёт сюда, и телепорт с карты по T
 * тоже. Причина перемещения решает правило «кто может», паузу и разогрев (раздел {@code places} правил карты, у телепорта с
 * карты свои правило и пауза); дальше проверка привата ({@link ClaimsApi}, правила 4.4), разогрев со срывом уроном,
 * загрузка чанков места под тикетом без ожидания в главном потоке, безопасная посадка ({@link SafeSpot}), запись истории
 * «назад» и пауза. Реализует контракт ядра {@link TravelApi}.
 *
 * <p>Если чанки места ещё не загружены, перенос идёт позже, а {@link #teleport} уже ответил {@link Result#done()}: отказ
 * после загрузки (нет безопасного места) игрок получает строкой уведомления. Только поток сервера.
 */
public final class Travel implements TravelApi {
    public static final Travel INSTANCE = new Travel();

    /** Причина телепорта с карты по T: своё правило и пауза ({@link MapRules}). */
    static final String MAP_KEY = "map";
    /** Сколько переносов одновременно ждут загрузки на всём сервере. */
    private static final int MAX_PENDING = 6;
    private static final TicketType<ChunkPos> TICKET = TicketType.create("rysmap_travel", java.util.Comparator.comparingLong(ChunkPos::toLong), 200);

    /** Чем кончился перенос: для телепорта с карты (пакет {@code TeleportResult}) и команд. */
    public interface Done {
        /** @param code короткий код отказа ({@code unsafe}, {@code border}, {@code busy}, {@code claimed}…), пусто при успехе */
        void accept(boolean ok, Component why, String code);
    }

    /** Итог с кодом отказа для старого пакета карты. */
    record Res(Result result, String code) {
        static Res of(Result r) {
            return new Res(r, r.ok() ? "" : r.status() == Status.PAUSE ? "pause" : "denied");
        }
    }

    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    /** Когда игрока последний раз ранил другой игрок, мс. */
    private final Map<UUID, Long> pvp = new HashMap<>();

    private Travel() {}

    void clear() {
        cooldowns.clear();
        warmups.clear();
        pending.clear();
        pvp.clear();
    }

    // ---- контракт ----

    @Override
    public Result teleport(ServerPlayer player, Trip trip) {
        return go(player, trip, false, null).result();
    }

    @Override
    public int cooldown(ServerPlayer player, Reason reason) {
        if (bypass(player)) return 0;
        String key = key(reason);
        return cooldowns.left(player.getUUID(), key, pause(reason), System.currentTimeMillis());
    }

    @Override
    public void rememberBack(ServerPlayer player, Spot from, Reason reason) {
        PlaceRules rules = MapServer.rules().places;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PlaceStore store = PlaceStore.get(server);
        store.book().pushBack(player.getUUID(), PlaceBook.Back.of(from.dimension().location().toString(), from.x(), from.y(), from.z(), from.yRot(), from.xRot(),
            from.hasFacing(), reason.id(), System.currentTimeMillis()), rules.backSize, rules.backDeath);
        store.touch();
        PlacesServer.changed(player);
    }

    @Override
    public void cancelWarmup(ServerPlayer player) {
        Warmup w = warmups.remove(player.getUUID());
        if (w != null) PlacesServer.note(player, PlacesServer.Note.WARMUP_END, Component.empty(), 0, w.trip.reason().id());
    }

    @Override
    public boolean present() {
        return true;
    }

    // ---- правила по причине ----

    /** Функция блока «Перемещения» для причины или null (карта, персонал, чужие причины). */
    static PlaceRules.@Nullable Fn fn(Reason r) {
        String id = r.id();
        return switch (id) {
            case "home" -> PlaceRules.Fn.HOME;
            case "team_home" -> PlaceRules.Fn.TEAM_HOME;
            case "place" -> PlaceRules.Fn.PLACE;
            case "back", "death" -> PlaceRules.Fn.BACK;
            case "spawn", "bed" -> PlaceRules.Fn.SPAWN;
            case "random" -> PlaceRules.Fn.RANDOM;
            case "request" -> PlaceRules.Fn.REQUEST;
            default -> null;
        };
    }

    static String key(Reason r) {
        PlaceRules.Fn f = fn(r);
        return f != null ? f.id : r.id();
    }

    static int pause(Reason r) {
        if (r.equals(Reason.MAP)) return MapServer.rules().pause;
        PlaceRules.Fn f = fn(r);
        return f == null ? 0 : MapServer.rules().places.row(f).pause;
    }

    static int warmup(Reason r) {
        PlaceRules.Fn f = fn(r);
        return f == null ? 0 : MapServer.rules().places.row(f).warmup;
    }

    /** Право «без паузы»: по умолчанию у оператора, как у карты раньше. */
    static boolean bypass(ServerPlayer player) {
        return PlacesServer.COOLDOWN_BYPASS.test(player);
    }

    /** Правило «кто» пускает игрока к функции. */
    public static boolean allowed(ServerPlayer player, PlaceRules.Fn fn) {
        return switch (MapServer.rules().places.row(fn).who) {
            case OFF -> false;
            case ALL -> true;
            case MODERATORS -> Roles.atLeast(player, Roles.MODERATOR);
            case OPERATORS -> Roles.atLeast(player, Roles.OPERATOR);
        };
    }

    /** Сколько секунд паузы по функции осталось у игрока (для пакета мест). */
    int left(ServerPlayer player, PlaceRules.Fn fn) {
        if (bypass(player)) return 0;
        return cooldowns.left(player.getUUID(), fn.id, MapServer.rules().places.row(fn).pause, System.currentTimeMillis());
    }

    int mapLeft(ServerPlayer player) {
        if (bypass(player)) return 0;
        return cooldowns.left(player.getUUID(), MAP_KEY, MapServer.rules().pause, System.currentTimeMillis());
    }

    void mark(ServerPlayer player, String key) {
        if (!bypass(player)) cooldowns.mark(player.getUUID(), key, System.currentTimeMillis());
    }

    /** Нужна ли проверка привата у места назначения (правила 4.4). */
    static boolean claimsChecked(Reason r, ServerPlayer player) {
        return switch (r.id()) {
            case "home", "team_home", "back", "other" -> true;
            // прыжок с карты в любое место: оператору без проверки, ему земля нужна для работы
            case "map" -> !Roles.atLeast(player, Roles.OPERATOR);
            default -> false; // гибель, места сервера, спавн, кровать, случайное (садит на ничью), запрос (согласие хозяина), персонал
        };
    }

    /** Отказ по привату: «Дом на земле Барсуков, вход закрыт». Null, если можно. */
    static @Nullable Component claimDenied(ServerPlayer player, ServerLevel level, BlockPos pos, @Nullable Component label) {
        ClaimsApi claims = ClaimsApi.get();
        UUID owner = claims.ownerAt(level.dimension(), pos.getX() >> 4, pos.getZ() >> 4);
        if (owner == null || claims.can(player.getUUID(), level.dimension(), pos, ClaimsApi.Action.USE)) return null;
        Component team = TeamsApi.get().name(owner);
        return label == null ? Component.translatable("rysmap.travel.claimed", team) : Component.translatable("rysmap.travel.claimed_named", label, team);
    }

    // ---- перенос ----

    /**
     * Перенос по правилам.
     *
     * @param column телепорт с карты: известен только столбец, высота ({@code spot.y}) лишь подсказка или
     *               {@link SafeSpot#NO_HINT}
     * @param done   итог переноса после загрузки; null — отказ после загрузки придёт игроку строкой
     */
    Res go(ServerPlayer player, Trip trip, boolean column, @Nullable Done done) {
        return go(player, trip, column, done, null);
    }

    /** То же для случайного места: {@code retry} даёт следующий столбец, если этот не годится. */
    Res go(ServerPlayer player, Trip trip, boolean columnOnly, @Nullable Done done, @Nullable IntFunction<int[]> retry) {
        MinecraftServer server = player.getServer();
        // высота SafeSpot.NO_HINT: место поставлено с карты, ищется посадка в столбце
        boolean column = columnOnly || trip.spot().y() == SafeSpot.NO_HINT;
        if (server == null) return new Res(Result.denied(Component.empty()), "state");
        Reason reason = trip.reason();
        Component label = trip.label();
        if (player.isSpectator() || !player.isAlive()) return new Res(Result.denied(Component.translatable("rysmap.tp.state")), "state");
        PlaceRules rules = MapServer.rules().places;
        PlaceRules.Fn fn = fn(reason);
        boolean staff = reason.equals(Reason.STAFF);
        // запрос к игроку: право «кто» проверено у того, кто просил; переносят и того, кого позвали
        if (fn != null && fn != PlaceRules.Fn.REQUEST && !allowed(player, fn)) {
            String code = rules.row(fn).who == PlaceRules.Who.OFF ? "off" : "ops";
            return new Res(Result.denied(Component.translatable("rysmap.travel." + code)), code);
        }
        if (reason.equals(Reason.MAP)) {
            if (MapServer.rules().teleport == MapRules.Teleport.OFF) return new Res(Result.denied(Component.translatable("rysmap.tp.off")), "off");
            if (!MapServer.canTeleport(player)) return new Res(Result.denied(Component.translatable("rysmap.tp.ops")), "ops");
        }
        if (fn != null && rules.pvpDelay > 0 && !staff) {
            Long hurt = pvp.get(player.getUUID());
            long left = hurt == null ? 0 : hurt + rules.pvpDelay * 1000L - System.currentTimeMillis();
            if (left > 0) return new Res(Result.denied(Component.translatable("rysmap.travel.pvp", (left + 999) / 1000)), "pvp");
        }
        String key = key(reason);
        if (!trip.skipCooldown() && !staff) {
            int left = bypass(player) ? 0 : cooldowns.left(player.getUUID(), key, pause(reason), System.currentTimeMillis());
            if (left > 0) return new Res(Result.pause(left), "pause");
        }
        Spot spot = trip.spot();
        ServerLevel level = server.getLevel(spot.dimension());
        if (level == null)
            return new Res(Result.denied(Component.translatable("ryscore.travel.no_dimension", spot.dimension().location().toString())), "dimension");
        if (reason.equals(Reason.MAP) && !Teleports.dimensionAllowed(player, level, MapServer.rules()))
            return new Res(Result.denied(Component.translatable("rysmap.tp.dimension")), "dimension");
        BlockPos pos = BlockPos.containing(spot.x(), column ? 64 : spot.y(), spot.z());
        if (!level.getWorldBorder().isWithinBounds(pos)) return new Res(Result.denied(Component.translatable("rysmap.tp.border")), "border");
        if (!trip.skipClaims() && claimsChecked(reason, player)) {
            Component why = claimDenied(player, level, pos, label);
            if (why != null) return new Res(Result.denied(why), "claimed");
        }
        int warm = trip.skipWarmup() || staff ? 0 : warmup(reason);
        if (warm > 0) {
            warmups.put(player.getUUID(), new Warmup(trip, column, done, retry, player.position(), server.getTickCount() + warm * 20));
            PlacesServer.note(player, PlacesServer.Note.WARMUP, label == null ? Component.empty() : label, warm, reason.id());
            return new Res(Result.warmup(warm), "");
        }
        return start(player, trip, level, column, done, retry);
    }

    /** Разогрев: перенос через N тиков, если урон (или движение, если так решил сервер) не сорвал. */
    record Warmup(Trip trip, boolean column, @Nullable Done done, @Nullable IntFunction<int[]> retry, net.minecraft.world.phys.Vec3 from, int at) {}

    void tick(MinecraftServer server) {
        if (warmups.isEmpty()) return;
        boolean move = MapServer.rules().places.moveCancels;
        Iterator<Map.Entry<UUID, Warmup>> it = warmups.entrySet().iterator();
        List<Map.Entry<ServerPlayer, Warmup>> ready = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Warmup> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Warmup w = e.getValue();
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            if (move && p.position().distanceToSqr(w.from) > 0.25) {
                it.remove();
                PlacesServer.note(p, PlacesServer.Note.WARMUP_END, Component.translatable("rysmap.travel.moved"), 0, w.trip.reason().id());
                if (w.done != null) w.done.accept(false, Component.translatable("rysmap.travel.moved"), "moved");
                continue;
            }
            if (server.getTickCount() >= w.at) {
                it.remove();
                ready.add(Map.entry(p, w));
            }
        }
        for (Map.Entry<ServerPlayer, Warmup> e : ready) {
            ServerPlayer p = e.getKey();
            Warmup w = e.getValue();
            PlacesServer.note(p, PlacesServer.Note.WARMUP_END, Component.empty(), 0, w.trip.reason().id());
            ServerLevel level = server.getLevel(w.trip.spot().dimension());
            if (level == null) continue;
            Res r = start(p, w.trip, level, w.column, w.done, w.retry);
            if (!r.result().ok()) fail(p, w.done, r.result().message(), r.code());
        }
    }

    /** Урон игроку: срывает разогрев; урон от другого игрока запоминается для правила «после урона». */
    void hurt(ServerPlayer player, boolean byPlayer) {
        if (byPlayer) {
            boolean was = pvpLeft(player) > 0;
            pvp.put(player.getUUID(), System.currentTimeMillis());
            // клиенту: места в колесе серые, пока идёт отсчёт «после урона от игрока»
            if (!was && MapServer.rules().places.pvpDelay > 0) PlacesServer.changed(player);
        }
        Warmup w = warmups.remove(player.getUUID());
        if (w != null) {
            PlacesServer.note(player, PlacesServer.Note.WARMUP_END, Component.translatable("rysmap.travel.hurt"), 0, w.trip.reason().id());
            if (w.done != null) w.done.accept(false, Component.translatable("rysmap.travel.hurt"), "hurt");
        }
    }

    /** Сколько секунд ещё нельзя телепортироваться после урона от игрока (правило «после урона»). */
    int pvpLeft(ServerPlayer player) {
        int delay = MapServer.rules().places.pvpDelay;
        Long hurt = pvp.get(player.getUUID());
        if (delay <= 0 || hurt == null) return 0;
        long left = hurt + delay * 1000L - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    boolean warming(ServerPlayer player) {
        return warmups.containsKey(player.getUUID());
    }

    /**
     * Загрузка чанков места и перенос. {@code retry} даёт следующий столбец для случайного места ({@code attempt} → {x, z}
     * или null, когда попытки кончились).
     */
    Res start(ServerPlayer player, Trip trip, ServerLevel level, boolean column, @Nullable Done done, @Nullable IntFunction<int[]> retry) {
        MinecraftServer server = player.getServer();
        if (server == null) return new Res(Result.denied(Component.empty()), "state");
        if (trip.skipSafeSpot()) {
            arrive(player, trip, level, trip.spot().x(), trip.spot().y(), trip.spot().z(), done);
            return Res.of(Result.done());
        }
        int x = (int) Math.floor(trip.spot().x()), z = (int) Math.floor(trip.spot().z());
        if (loaded(level, x, z)) {
            Res r = land(player, trip, level, column, x, z, done, retry, 0);
            return r;
        }
        if (pending.contains(player.getUUID()) || pending.size() >= MAX_PENDING) return new Res(Result.denied(Component.translatable("rysmap.tp.busy")), "busy");
        load(player, trip, level, column, x, z, done, retry, 0);
        return Res.of(Result.done());
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        int r = SafeSpot.RADIUS;
        for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++)
            for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
        return true;
    }

    /** Чанки грузятся и при нужде создаются асинхронно под тикетом; главный поток не ждёт. */
    private void load(ServerPlayer player, Trip trip, ServerLevel level, boolean column, int x, int z, @Nullable Done done,
                      @Nullable IntFunction<int[]> retry, int attempt) {
        MinecraftServer server = level.getServer();
        int r = SafeSpot.RADIUS;
        List<CompletableFuture<?>> loads = new ArrayList<>();
        List<ChunkPos> held = new ArrayList<>();
        for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
            for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(TICKET, cp, 1, cp);
                held.add(cp);
                loads.add(level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true));
            }
        }
        UUID id = player.getUUID();
        pending.add(id);
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).orTimeout(15, TimeUnit.SECONDS).whenCompleteAsync((v, err) -> {
            pending.remove(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            try {
                if (p == null) return;
                if (err != null) {
                    if (trip.reason().equals(Reason.MAP)) mark(p, MAP_KEY);
                    fail(p, done, Component.translatable("rysmap.tp.unsafe"), "unsafe");
                    return;
                }
                Res res = land(p, trip, level, column, x, z, done, retry, attempt);
                if (!res.result().ok()) fail(p, done, res.result().message(), res.code());
            } catch (RuntimeException e) {
                RYSMap.LOG.warn("[rysmap] перенос {} не прошёл: {}", p == null ? id : p.getGameProfile().getName(), e.toString());
                if (p != null) fail(p, done, Component.translatable("rysmap.tp.unsafe"), "unsafe");
            } finally {
                for (ChunkPos cp : held) level.getChunkSource().removeRegionTicket(TICKET, cp, 1, cp);
            }
        }, server);
    }

    /** Чанки загружены: поиск места и перенос. Главный поток. */
    private Res land(ServerPlayer player, Trip trip, ServerLevel level, boolean column, int x, int z, @Nullable Done done,
                     @Nullable IntFunction<int[]> retry, int attempt) {
        if (player.isSpectator() || !player.isAlive()) return new Res(Result.denied(Component.translatable("rysmap.tp.state")), "state");
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        int maxY = ceiling ? Math.min(level.getMaxBuildHeight(), minY + level.dimensionType().logicalHeight()) - 1 : level.getMaxBuildHeight() - 1;
        int hint;
        if (column) {
            hint = (int) trip.spot().y();
            if (ceiling && hint == SafeSpot.NO_HINT && player.level() == level) hint = player.getBlockY();
        } else {
            hint = (int) Math.floor(trip.spot().y() + 0.01);
        }
        int[] spot = SafeSpot.find(new Teleports.LevelProbe(level, ceiling), x, z, minY, maxY, ceiling, hint);
        boolean ok = spot != null && level.getWorldBorder().isWithinBounds(new BlockPos(spot[0], spot[1], spot[2]));
        if (ok && retry != null && ClaimsApi.get().ownerAt(level.dimension(), spot[0] >> 4, spot[2] >> 4) != null) ok = false;
        if (!ok) {
            if (retry != null) {
                int[] next = retry.apply(attempt + 1);
                if (next != null) {
                    if (loaded(level, next[0], next[1])) return land(player, trip, level, column, next[0], next[1], done, retry, attempt + 1);
                    load(player, trip, level, column, next[0], next[1], done, retry, attempt + 1);
                    return Res.of(Result.done());
                }
            }
            RYSMap.LOG.info("[rysmap] перенос {} ({}): в {} {} {} нет безопасного места", player.getGameProfile().getName(), trip.reason(),
                level.dimension().location(), x, z);
            if (trip.reason().equals(Reason.MAP)) mark(player, MAP_KEY);
            return new Res(Result.denied(Component.translatable(retry != null ? "rysmap.travel.no_random" : "rysmap.tp.unsafe")), "unsafe");
        }
        double tx = spot[0] + 0.5, ty = spot[1], tz = spot[2] + 0.5;
        // точное место (дом стоит там, где стоял игрок): если безопасный блок тот же, ноги встают туда же
        if (!column && spot[0] == x && spot[2] == z && spot[1] == hint) {
            tx = trip.spot().x();
            ty = trip.spot().y();
            tz = trip.spot().z();
        }
        arrive(player, trip, level, tx, ty, tz, done);
        return Res.of(Result.done());
    }

    private void arrive(ServerPlayer player, Trip trip, ServerLevel level, double x, double y, double z, @Nullable Done done) {
        Spot from = Spot.of(player);
        Spot to = trip.spot();
        float yRot = to.hasFacing() ? to.yRot() : player.getYRot(), xRot = to.hasFacing() ? to.xRot() : player.getXRot();
        player.teleportTo(level, x, y, z, yRot, xRot);
        player.resetFallDistance();
        if (trip.recordBack()) rememberBack(player, from, trip.reason());
        if (!trip.skipCooldown() && !trip.reason().equals(Reason.STAFF)) mark(player, key(trip.reason()));
        RYSMap.LOG.info("[rysmap] перенос {} ({}): {} {} {} {}", player.getGameProfile().getName(), trip.reason(), level.dimension().location(),
            (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (done != null) done.accept(true, Component.empty(), "");
        PlacesServer.changed(player);
    }

    private void fail(ServerPlayer player, @Nullable Done done, Component why, String code) {
        if (done != null) done.accept(false, why, code);
        else PlacesServer.note(player, PlacesServer.Note.DENIED, why, 0, "");
    }
}

package works.repka.rysmap.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.team.TeamsApi;
import works.repka.ryscore.travel.TravelApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.PlaceNet;
import works.repka.rysmap.places.PlaceBook;
import works.repka.rysmap.places.PlaceRules;

/**
 * Серверная часть «Мест» (0.2.0, план Essentials 4): права, контракт перемещений, события (гибель, чужой телепорт, урон,
 * вход и выход), рассылка мест клиентам, разбор действий с клиента. Логика мест в {@link Places}, запросы в
 * {@link Requests}, команды в {@link PlaceCommands}, движок в {@link Travel}.
 */
public final class PlacesServer {
    /** Ставить, менять и удалять места сервера: модератор и оператор. */
    public static final Perms.Node PLACE_MANAGE = Perms.node("rysmap", "place.manage", Roles.MODERATOR, Component.translatable("rysmap.perm.place.manage"));
    /** Без паузы: оператор, как у карты раньше. */
    public static final Perms.Node COOLDOWN_BYPASS = Perms.node("rysmap", "cooldown.bypass", Roles.OPERATOR, Component.translatable("rysmap.perm.cooldown.bypass"));
    /** Права команды (таблица RYSTeams): ставить дом команды; к своим без запроса. */
    public static final String TEAM_HOME_PERM = "rysmap:team_home", TEAM_TP_PERM = "rysmap:team_tp";

    /** Вид строки уведомления. */
    public enum Note { DENIED, WARMUP, WARMUP_END, DONE }

    private static int tick;
    /** Кому разослать места на следующем тике (сбор правок одного тика в одну рассылку). */
    private static final Set<UUID> DIRTY = new HashSet<>();
    private static boolean dirtyAll;

    private PlacesServer() {}

    /** Конструктор мода, обе стороны: права сервера и команды объявляются до запуска сервера. */
    public static void declare() {
        TeamsApi teams = TeamsApi.get();
        teams.registerPermission(TEAM_HOME_PERM, Component.translatable("rysmap.team_perm.team_home"), Set.of(TeamsApi.LEADER, TeamsApi.OFFICER));
        teams.registerPermission(TEAM_TP_PERM, Component.translatable("rysmap.team_perm.team_tp"), Set.of(TeamsApi.LEADER, TeamsApi.OFFICER, TeamsApi.MEMBER));
    }

    public static void init() {
        declare();
        TravelApi.provide(Travel.INSTANCE);
        works.repka.ryscore.migrate.Migrations.register(new works.repka.rysmap.migrate.PlacesStep());
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((RegisterCommandsEvent e) -> PlaceCommands.register(e.getDispatcher(), ftbEssentials()));
        // команды меняются: дом команды и права участников приходят им заново
        TeamsApi.listen(new TeamsApi.Listener() {
            @Override
            public void onJoined(UUID team, UUID player, boolean carryProgress) {
                dirtyAll = true;
            }

            @Override
            public void onLeft(UUID team, UUID player, boolean keepCopy) {
                dirtyAll = true;
            }

            @Override
            public void onDisbanded(UUID team) {
                dirtyAll = true;
            }

            @Override
            public void onMerged(UUID from, UUID into) {
                dirtyAll = true;
            }

            @Override
            public void onRoleChanged(UUID team, UUID player) {
                dirtyAll = true;
            }
        });
        bus.addListener((net.neoforged.neoforge.event.server.ServerStartingEvent e) -> {
            Requests.clear();
            DIRTY.clear();
            dirtyAll = false;
            if (ftbEssentials()) RYSMap.LOG.info("[rysmap] FTB Essentials загружен: команды мест только /rys place …, перенос places ждёт");
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) sync(p);
        });
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) changed(p);
        });
        bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) changed(p);
        });
        bus.addListener((LivingDeathEvent e) -> {
            if (!(e.getEntity() instanceof ServerPlayer p) || e.isCanceled()) return;
            Travel.INSTANCE.cancelWarmup(p);
            TravelApi.Spot here = TravelApi.Spot.of(p);
            Travel.INSTANCE.rememberBack(p, here, TravelApi.Reason.DEATH);
        });
        bus.addListener((LivingDamageEvent.Post e) -> {
            if (!(e.getEntity() instanceof ServerPlayer p) || e.getNewDamage() <= 0) return;
            boolean byPlayer = e.getSource().getEntity() instanceof ServerPlayer other && other != p;
            Travel.INSTANCE.hurt(p, byPlayer);
        });
        // чужие телепорты (команды /tp и /spreadplayers) пишут место отправления в «назад»; жемчуг и хорус нет, они рядом
        bus.addListener((EntityTeleportEvent.TeleportCommand e) -> foreign(e));
        bus.addListener((EntityTeleportEvent.SpreadPlayersCommand e) -> foreign(e));
        bus.addListener((ServerTickEvent.Post e) -> {
            MinecraftServer server = e.getServer();
            Travel.INSTANCE.tick(server);
            if (++tick >= 20) {
                tick = 0;
                Requests.tick(server);
            }
            flush(server);
        });
    }

    private static void foreign(EntityTeleportEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (e.getPrev().distanceToSqr(e.getTarget()) < 16 * 16) return;
        Travel.INSTANCE.rememberBack(p, TravelApi.Spot.of(p), TravelApi.Reason.OTHER);
    }

    /** Стоит ли FTB Essentials: тогда корневые команды его, наши только под {@code /rys place}, перенос ждёт. */
    public static boolean ftbEssentials() {
        return ModList.get().isLoaded("ftbessentials");
    }

    /** Команда для подсказки и кнопок чата с учётом FTB Essentials: {@code /home} или {@code /rys place home}. */
    public static String cmd(String sub) {
        return ftbEssentials() ? "/rys place " + sub : "/" + sub;
    }

    static void log(ServerPlayer p, String what) {
        RYSMap.LOG.info("[rysmap] {} {}", p.getGameProfile().getName(), what);
    }

    // ---- рассылка ----

    static void send(ServerPlayer p, CustomPacketPayload payload) {
        if (p.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(p, payload);
    }

    /** Строка уведомления; клиенту без «Мест» — строка чата (отказ и итог; разогрев одной строкой). */
    public static void note(ServerPlayer p, Note kind, Component text, int seconds, String reason) {
        if (p.connection.hasChannel(PlaceNet.Note.TYPE)) {
            PacketDistributor.sendToPlayer(p, new PlaceNet.Note(kind.ordinal(), text, seconds, reason));
            return;
        }
        switch (kind) {
            case DENIED, DONE -> {
                if (!text.getString().isEmpty()) p.sendSystemMessage(text);
            }
            case WARMUP -> p.sendSystemMessage(Component.translatable("rysmap.travel.warmup_chat", text, seconds));
            case WARMUP_END -> {
                if (!text.getString().isEmpty()) p.sendSystemMessage(text);
            }
        }
    }

    /** Места игрока изменились: разослать на этом тике. */
    public static void changed(ServerPlayer p) {
        DIRTY.add(p.getUUID());
    }

    static void changedTeam(MinecraftServer server, UUID team) {
        for (UUID id : TeamsApi.get().members(team)) DIRTY.add(id);
    }

    /** Места сервера или правила: всем. */
    public static void changedAll(MinecraftServer server) {
        dirtyAll = true;
    }

    private static void flush(MinecraftServer server) {
        if (!dirtyAll && DIRTY.isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (dirtyAll || DIRTY.contains(p.getUUID())) sync(p);
        DIRTY.clear();
        dirtyAll = false;
    }

    /**
     * Места игрока клиенту: свои дома (с замком, если землю заняли), дом команды, видимые места сервера, «назад», спавн и
     * кровать, правила и паузы.
     */
    public static void sync(ServerPlayer p) {
        if (!p.connection.hasChannel(PlaceNet.Sync.TYPE)) return;
        PacketDistributor.sendToPlayer(p, new PlaceNet.Sync(json(p).toString()));
    }

    static JsonObject json(ServerPlayer p) {
        MinecraftServer server = p.server;
        PlaceBook book = PlaceStore.get(server).book();
        PlaceRules rules = MapServer.rules().places;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("ftb", ftbEssentials());
        o.addProperty("manage", PLACE_MANAGE.test(p));
        o.addProperty("op", MapServer.op(p));
        o.addProperty("bypass", Travel.bypass(p));
        boolean teams = Places.teams();
        o.addProperty("teams", teams);
        TeamsApi api = TeamsApi.get();
        UUID team = api.teamOf(p.getUUID());
        o.addProperty("teamHomePerm", teams && api.can(p.getUUID(), TEAM_HOME_PERM));
        o.addProperty("teamTp", teams && api.can(p.getUUID(), TEAM_TP_PERM));
        o.add("rules", rules.toJson());
        JsonObject allowed = new JsonObject(), left = new JsonObject();
        for (PlaceRules.Fn f : PlaceRules.Fn.values()) {
            allowed.addProperty(f.id, Travel.allowed(p, f));
            left.addProperty(f.id, Travel.INSTANCE.left(p, f));
        }
        o.add("allowed", allowed);
        o.add("left", left);
        o.addProperty("pvp", Travel.INSTANCE.pvpLeft(p));
        JsonArray homes = new JsonArray();
        for (PlaceBook.Place h : book.homes(p.getUUID())) {
            JsonObject j = place(h);
            String lock = lock(p, h);
            if (lock != null) j.addProperty("lock", lock);
            homes.add(j);
        }
        o.add("homes", homes);
        if (teams) {
            PlaceBook.Place th = Places.teamHome(server, team);
            if (th != null) {
                JsonObject j = place(th);
                j.addProperty("team", api.name(team).getString());
                String lock = lock(p, th);
                if (lock != null) j.addProperty("lock", lock);
                o.add("teamHome", j);
            }
        }
        JsonArray places = new JsonArray();
        for (PlaceBook.Place w : book.places()) if (Places.sees(p, w)) places.add(place(w));
        o.add("places", places);
        JsonArray back = new JsonArray();
        for (PlaceBook.Back b : book.back(p.getUUID())) {
            JsonObject j = new JsonObject();
            j.addProperty("dim", b.dim);
            j.addProperty("x", b.x);
            j.addProperty("y", b.y);
            j.addProperty("z", b.z);
            j.addProperty("reason", b.reason);
            j.addProperty("at", b.at);
            back.add(j);
        }
        o.add("back", back);
        BlockPos s = server.overworld().getSharedSpawnPos();
        o.add("spawn", pos(server.overworld().dimension().location().toString(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5));
        BlockPos bed = p.getRespawnPosition();
        if (bed != null) o.add("bed", pos(p.getRespawnDimension().location().toString(), bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5));
        return o;
    }

    /** Имя команды, если дом на чужой закрытой земле (правило 4.4.2), иначе null. */
    static String lock(ServerPlayer p, PlaceBook.Place h) {
        ResourceLocation d = ResourceLocation.tryParse(h.dim);
        if (d == null) return null;
        ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, d);
        BlockPos pos = BlockPos.containing(h.x, h.y, h.z);
        ClaimsApi claims = ClaimsApi.get();
        UUID owner = claims.ownerAt(key, pos.getX() >> 4, pos.getZ() >> 4);
        if (owner == null || claims.can(p.getUUID(), key, pos, ClaimsApi.Action.USE)) return null;
        return TeamsApi.get().name(owner).getString();
    }

    static JsonObject pos(String dim, double x, double y, double z) {
        JsonObject j = new JsonObject();
        j.addProperty("dim", dim);
        j.addProperty("x", x);
        j.addProperty("y", y);
        j.addProperty("z", z);
        return j;
    }

    static JsonObject place(PlaceBook.Place w) {
        JsonObject j = pos(w.dim, w.x, w.y, w.z);
        j.addProperty("name", w.name);
        j.addProperty("icon", w.icon);
        j.addProperty("color", w.color);
        if (!w.note.isEmpty()) j.addProperty("note", w.note);
        j.addProperty("vis", w.vis.name().toLowerCase(java.util.Locale.ROOT));
        if (!w.byName.isEmpty()) j.addProperty("by", w.byName);
        return j;
    }

    // ---- действия с клиента ----

    /** Действие с карты или колеса: итог строкой уведомления (успешный телепорт молча). */
    public static void op(ServerPlayer p, PlaceNet.Op op) {
        JsonObject a;
        try {
            a = op.json().isEmpty() ? new JsonObject() : JsonParser.parseString(op.json()).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        Places.Reply r;
        try {
            r = run(p, op.op(), a);
        } catch (RuntimeException e) {
            RYSMap.LOG.warn("[rysmap] действие места {} от {} не прошло: {}", op.op(), p.getGameProfile().getName(), e.toString());
            return;
        }
        if (r == null || r.text().getString().isEmpty()) return;
        note(p, r.ok() ? Note.DONE : Note.DENIED, r.text(), 0, op.op());
    }

    private static Places.Reply run(ServerPlayer p, String op, JsonObject a) {
        String name = str(a, "name");
        return switch (op) {
            case "home" -> Places.home(p, name);
            case "sethome" -> Places.setHome(p, name, at(a, p));
            case "delhome" -> Places.delHome(p, name);
            case "teamhome" -> Places.teamHome(p);
            case "setteamhome" -> Places.setTeamHome(p, at(a, p));
            case "delteamhome" -> Places.delTeamHome(p);
            case "warp" -> Places.warp(p, name);
            case "setwarp" -> Places.setWarp(p, name, at(a, p), a.has("icon") ? meta(a) : null);
            case "editwarp" -> Places.editWarp(p, name, meta(a));
            case "delwarp" -> Places.delWarp(p, name);
            case "back" -> Places.back(p, a.has("index") ? a.get("index").getAsInt() : 0);
            case "delback" -> {
                int i = a.has("index") ? a.get("index").getAsInt() : -1;
                if (PlaceStore.get(p.server).book().removeBack(p.getUUID(), i)) {
                    PlaceStore.get(p.server).touch();
                    changed(p);
                }
                yield null;
            }
            case "spawn" -> Places.spawn(p);
            case "bed" -> Places.bed(p);
            case "rtp" -> Places.random(p);
            case "tpa", "tpahere" -> {
                ServerPlayer to = player(p, a);
                yield to == null ? Places.Reply.fail(Component.translatable("rysmap.request.offline")) : Requests.send(p, to, op.equals("tpahere"));
            }
            case "accept", "decline" -> Requests.answer(p, player(p, a), op.equals("accept"));
            case "cancel" -> Requests.cancel(p, player(p, a));
            case "rules" -> setRules(p, a);
            default -> null;
        };
    }

    /** Оператор правит блок «Перемещения». */
    private static Places.Reply setRules(ServerPlayer p, JsonObject a) {
        if (!MapServer.op(p)) {
            RYSMap.LOG.warn("[rysmap] {} пытался изменить правила мест без прав оператора", p.getGameProfile().getName());
            return null;
        }
        MapServer.setPlaceRules(p, PlaceRules.fromJson(a));
        return null;
    }

    private static String str(JsonObject a, String key) {
        return a.has(key) && !a.get(key).isJsonNull() ? a.get(key).getAsString() : "";
    }

    private static ServerPlayer player(ServerPlayer p, JsonObject a) {
        if (a.has("id")) {
            try {
                return p.server.getPlayerList().getPlayer(UUID.fromString(a.get("id").getAsString()));
            } catch (RuntimeException ignored) {
                // не UUID
            }
        }
        if (a.has("player")) return p.server.getPlayerList().getPlayerByName(a.get("player").getAsString());
        return null;
    }

    /** Место из действия ({@code dim x y z}); без него null — где стоит игрок. */
    private static PlaceBook.Place at(JsonObject a, ServerPlayer p) {
        if (!a.has("dim") || !a.has("x") || !a.has("z")) return null;
        PlaceBook.Place place = new PlaceBook.Place();
        place.dim = a.get("dim").getAsString();
        place.x = a.get("x").getAsDouble();
        place.z = a.get("z").getAsDouble();
        // с карты высота неизвестна: место помечается «поверхность», телепорт ищет посадку в столбце (дом встаёт на землю)
        place.y = a.has("y") ? a.get("y").getAsDouble() : p.getY();
        place.surface = !a.has("y");
        place.yRot = p.getYRot();
        place.xRot = p.getXRot();
        place.facing = false;
        return place;
    }

    private static PlaceBook.Place meta(JsonObject a) {
        PlaceBook.Place m = new PlaceBook.Place();
        m.name = str(a, "newName");
        m.icon = a.has("icon") ? str(a, "icon") : "flag";
        m.color = a.has("color") ? a.get("color").getAsInt() : -1;
        m.note = str(a, "note");
        m.vis = switch (str(a, "vis")) {
            case "moderators" -> PlaceBook.Vis.MODERATORS;
            case "operators" -> PlaceBook.Vis.OPERATORS;
            default -> PlaceBook.Vis.ALL;
        };
        return m;
    }
}

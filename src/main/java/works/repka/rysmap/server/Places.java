package works.repka.rysmap.server;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.team.TeamsApi;
import works.repka.ryscore.travel.TravelApi;
import works.repka.rysmap.places.PlaceBook;
import works.repka.rysmap.places.PlaceNames;
import works.repka.rysmap.places.PlaceRules;

/**
 * Что игрок делает с местами (план Essentials 4.1): дома, дом команды, места сервера, назад, спавн, кровать, случайное
 * место. Общая часть команд чата и действий с карты и колеса: каждый метод возвращает {@link Reply}, команда пишет его в
 * чат, действие с клиента показывает строкой уведомления. Перенос идёт через {@link Travel}. Только поток сервера.
 */
public final class Places {
    /** Ключ дома команды в данных команды ({@code TeamsApi.putData}). */
    public static final ResourceLocation TEAM_HOME = ResourceLocation.fromNamespaceAndPath("rysmap", "team_home");
    private static final Random RANDOM = new Random();

    private Places() {}

    /** Ответ игроку: удалось ли и что сказать (пусто: молча, например успешный телепорт). */
    public record Reply(boolean ok, Component text) {
        static Reply ok(Component text) {
            return new Reply(true, text);
        }

        static Reply fail(Component text) {
            return new Reply(false, text);
        }

        static Reply silent() {
            return new Reply(true, Component.empty());
        }

        public boolean silentOk() {
            return ok && text.getString().isEmpty();
        }
    }

    static PlaceStore store(ServerPlayer p) {
        return PlaceStore.get(p.server);
    }

    /** Итог переноса словами. */
    static Reply of(TravelApi.Result r, Component label) {
        return switch (r.status()) {
            case DONE, WARMUP -> Reply.silent();
            case PAUSE -> Reply.fail(Component.translatable("rysmap.travel.pause", label, r.seconds() / 60 + ":" + String.format("%02d", r.seconds() % 60)));
            case DENIED -> Reply.fail(r.message());
        };
    }

    static TravelApi.Spot spot(PlaceBook.Place p) {
        TravelApi.Spot s = TravelApi.Spot.of(ResourceLocation.parse(p.dim), p.x, p.y, p.z);
        return p.facing ? s.facing(p.yRot, p.xRot) : s;
    }

    static @Nullable ResourceLocation dim(String id) {
        return ResourceLocation.tryParse(id);
    }

    /** Место, где стоит игрок. */
    static PlaceBook.Place here(ServerPlayer p, String name) {
        return PlaceBook.Place.at(name, p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    /** Можно ли здесь поставить дом (правило 4.4.1): ничья земля, своя или где есть право «двери, кнопки, рычаги». */
    static @Nullable Component cannotSettle(ServerPlayer p, ResourceKey<Level> dim, BlockPos pos) {
        ClaimsApi claims = ClaimsApi.get();
        UUID owner = claims.ownerAt(dim, pos.getX() >> 4, pos.getZ() >> 4);
        if (owner == null || claims.can(p.getUUID(), dim, pos, ClaimsApi.Action.USE)) return null;
        return Component.translatable("rysmap.place.foreign_land", TeamsApi.get().name(owner));
    }

    // ---- дом ----

    public static Reply home(ServerPlayer p, String name) {
        PlaceBook.Place h = store(p).book().home(p.getUUID(), name);
        if (h == null) {
            List<PlaceBook.Place> all = store(p).book().homes(p.getUUID());
            if (all.isEmpty()) return Reply.fail(Component.translatable("rysmap.place.no_home", PlacesServer.cmd("sethome")));
            return Reply.fail(Component.translatable("rysmap.place.no_home_named", name == null ? "" : name, names(all)));
        }
        Component label = homeLabel(h);
        if (dim(h.dim) == null) return Reply.fail(Component.translatable("rysmap.place.bad_dimension", h.dim));
        TravelApi.Result r = Travel.INSTANCE.teleport(p, TravelApi.Trip.to(spot(h)).reason(TravelApi.Reason.HOME).label(label).build());
        return of(r, label);
    }

    static Component homeLabel(PlaceBook.Place h) {
        return PlaceBook.DEFAULT_HOME.equals(PlaceNames.key(h.name)) ? Component.translatable("rysmap.place.home") : Component.literal(h.name);
    }

    static String names(List<PlaceBook.Place> list) {
        StringBuilder b = new StringBuilder();
        for (PlaceBook.Place p : list) {
            if (!b.isEmpty()) b.append(", ");
            b.append(PlaceNames.quote(p.name));
        }
        return b.toString();
    }

    public static Reply setHome(ServerPlayer p, String name, PlaceBook.@Nullable Place at) {
        if (!Travel.allowed(p, PlaceRules.Fn.HOME)) return Reply.fail(Component.translatable("rysmap.travel.off"));
        PlaceBook.Place place = at != null ? at : here(p, name == null ? "" : name);
        place.name = name == null ? "" : name;
        place.icon = "home";
        place.at = System.currentTimeMillis();
        ResourceLocation d = dim(place.dim);
        if (d == null) return Reply.fail(Component.translatable("rysmap.place.bad_dimension", place.dim));
        Component no = cannotSettle(p, ResourceKey.create(Registries.DIMENSION, d), BlockPos.containing(place.x, place.y, place.z));
        if (no != null) return Reply.fail(no);
        int limit = MapServer.rules().places.homes;
        PlaceBook book = store(p).book();
        PlaceBook.Put put = book.setHome(p.getUUID(), place, limit);
        store(p).touch();
        PlacesServer.changed(p);
        return switch (put) {
            case ADDED -> Reply.ok(Component.translatable("rysmap.place.home_set", homeLabelOf(book, p, name)));
            case MOVED -> Reply.ok(Component.translatable("rysmap.place.home_moved", homeLabelOf(book, p, name)));
            case LIMIT -> Reply.fail(limit == 0 ? Component.translatable("rysmap.travel.off")
                : Component.translatable("rysmap.place.home_limit", limit, names(book.homes(p.getUUID()))));
            case BAD_NAME -> Reply.fail(Component.translatable("rysmap.place.bad_name"));
        };
    }

    private static Component homeLabelOf(PlaceBook book, ServerPlayer p, String name) {
        PlaceBook.Place h = book.home(p.getUUID(), name);
        if (h == null && book.homes(p.getUUID()).size() == 1) h = book.homes(p.getUUID()).get(0);
        return h == null ? Component.translatable("rysmap.place.home") : homeLabel(h);
    }

    public static Reply delHome(ServerPlayer p, String name) {
        PlaceBook.Place h = store(p).book().home(p.getUUID(), name);
        if (h == null) return Reply.fail(Component.translatable("rysmap.place.no_home_named", name == null ? "" : name, names(store(p).book().homes(p.getUUID()))));
        store(p).book().removeHome(p.getUUID(), h.name);
        store(p).touch();
        PlacesServer.changed(p);
        return Reply.ok(Component.translatable("rysmap.place.home_removed", homeLabel(h)));
    }

    public static Reply listHomes(ServerPlayer p) {
        List<PlaceBook.Place> all = store(p).book().homes(p.getUUID());
        if (all.isEmpty()) return Reply.ok(Component.translatable("rysmap.place.no_home", PlacesServer.cmd("sethome")));
        return Reply.ok(Component.translatable("rysmap.place.homes", all.size(), MapServer.rules().places.homes, list(p, all)));
    }

    static String list(ServerPlayer p, List<PlaceBook.Place> all) {
        StringBuilder b = new StringBuilder();
        for (PlaceBook.Place h : all) {
            if (!b.isEmpty()) b.append("; ");
            b.append(PlaceNames.quote(h.name)).append(' ').append((int) Math.floor(h.x)).append(' ').append((int) Math.floor(h.y)).append(' ')
                .append((int) Math.floor(h.z));
            if (!h.dim.equals(p.level().dimension().location().toString())) b.append(" (").append(h.dim).append(')');
        }
        return b.toString();
    }

    // ---- дом команды ----

    /** Есть ли команды (RYSTeams): без них дома команды нет. */
    static boolean teams() {
        return net.neoforged.fml.ModList.get().isLoaded("rysteams");
    }

    public static PlaceBook.@Nullable Place teamHome(MinecraftServer server, UUID team) {
        CompoundTag t = TeamsApi.get().getData(team, TEAM_HOME);
        if (t == null || !t.contains("dim")) return null;
        PlaceBook.Place p = PlaceBook.Place.at(t.getString("name"), t.getString("dim"), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
            t.getFloat("yRot"), t.getFloat("xRot"));
        p.byName = t.getString("byName");
        if (t.hasUUID("by")) p.by = t.getUUID("by");
        p.at = t.getLong("at");
        p.icon = "users";
        return p;
    }

    public static Reply teamHome(ServerPlayer p) {
        if (!teams()) return Reply.fail(Component.translatable("rysmap.place.no_teams"));
        UUID team = TeamsApi.get().teamOf(p.getUUID());
        PlaceBook.Place h = teamHome(p.server, team);
        if (h == null) return Reply.fail(Component.translatable("rysmap.place.no_team_home"));
        Component label = Component.translatable("rysmap.place.team_home");
        TravelApi.Result r = Travel.INSTANCE.teleport(p, TravelApi.Trip.to(spot(h)).reason(TravelApi.Reason.TEAM_HOME).label(label).build());
        return of(r, label);
    }

    public static Reply setTeamHome(ServerPlayer p, PlaceBook.@Nullable Place at) {
        if (!teams()) return Reply.fail(Component.translatable("rysmap.place.no_teams"));
        if (!Travel.allowed(p, PlaceRules.Fn.TEAM_HOME)) return Reply.fail(Component.translatable("rysmap.travel.off"));
        TeamsApi teams = TeamsApi.get();
        if (!teams.can(p.getUUID(), PlacesServer.TEAM_HOME_PERM)) return Reply.fail(Component.translatable("rysmap.place.team_home_perm"));
        PlaceBook.Place place = at != null ? at : here(p, "");
        ResourceLocation d = dim(place.dim);
        if (d == null) return Reply.fail(Component.translatable("rysmap.place.bad_dimension", place.dim));
        Component no = cannotSettle(p, ResourceKey.create(Registries.DIMENSION, d), BlockPos.containing(place.x, place.y, place.z));
        if (no != null) return Reply.fail(no);
        UUID team = teams.teamOf(p.getUUID());
        CompoundTag t = new CompoundTag();
        t.putString("dim", place.dim);
        t.putDouble("x", place.x);
        t.putDouble("y", place.y);
        t.putDouble("z", place.z);
        t.putFloat("yRot", place.yRot);
        t.putFloat("xRot", place.xRot);
        t.putUUID("by", p.getUUID());
        t.putString("byName", p.getGameProfile().getName());
        t.putLong("at", System.currentTimeMillis());
        teams.putData(team, TEAM_HOME, t);
        PlacesServer.changedTeam(p.server, team);
        return Reply.ok(Component.translatable("rysmap.place.team_home_set"));
    }

    public static Reply delTeamHome(ServerPlayer p) {
        if (!teams()) return Reply.fail(Component.translatable("rysmap.place.no_teams"));
        TeamsApi teams = TeamsApi.get();
        if (!teams.can(p.getUUID(), PlacesServer.TEAM_HOME_PERM)) return Reply.fail(Component.translatable("rysmap.place.team_home_perm"));
        UUID team = teams.teamOf(p.getUUID());
        if (teamHome(p.server, team) == null) return Reply.fail(Component.translatable("rysmap.place.no_team_home"));
        teams.putData(team, TEAM_HOME, new CompoundTag());
        PlacesServer.changedTeam(p.server, team);
        return Reply.ok(Component.translatable("rysmap.place.team_home_removed"));
    }

    // ---- места сервера ----

    /** Видит ли игрок место сервера. */
    public static boolean sees(ServerPlayer p, PlaceBook.Place place) {
        return switch (place.vis) {
            case ALL -> true;
            case MODERATORS -> Roles.atLeast(p, Roles.MODERATOR);
            case OPERATORS -> Roles.atLeast(p, Roles.OPERATOR);
        };
    }

    public static Reply warp(ServerPlayer p, String name) {
        PlaceBook.Place w = store(p).book().place(name);
        if (w == null || !sees(p, w)) return Reply.fail(Component.translatable("rysmap.place.no_place", name));
        if (dim(w.dim) == null) return Reply.fail(Component.translatable("rysmap.place.bad_dimension", w.dim));
        Component label = Component.literal(w.name);
        TravelApi.Result r = Travel.INSTANCE.teleport(p, TravelApi.Trip.to(spot(w)).reason(TravelApi.Reason.PLACE).label(label).build());
        return of(r, label);
    }

    public static Reply listWarps(ServerPlayer p) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (PlaceBook.Place w : store(p).book().places()) {
            if (!sees(p, w)) continue;
            if (!b.isEmpty()) b.append(", ");
            b.append(PlaceNames.quote(w.name));
            n++;
        }
        if (n == 0) return Reply.ok(Component.translatable("rysmap.place.no_places"));
        return Reply.ok(Component.translatable("rysmap.place.places", n, b.toString()));
    }

    /**
     * Поставить или переставить место сервера (право {@code rysmap.place.manage}). У переставленного места значок, цвет,
     * описание и «кто видит» остаются, если в {@code meta} их нет.
     */
    public static Reply setWarp(ServerPlayer p, String name, PlaceBook.@Nullable Place at, PlaceBook.@Nullable Place meta) {
        if (!PlacesServer.PLACE_MANAGE.test(p)) return Reply.fail(Component.translatable("rysmap.place.manage_perm"));
        String clean = PlaceNames.clean(name);
        if (clean.isEmpty()) return Reply.fail(Component.translatable("rysmap.place.bad_name"));
        PlaceBook book = store(p).book();
        PlaceBook.Place old = book.place(clean);
        PlaceBook.Place place = at != null ? at.copy() : here(p, clean);
        place.name = old != null ? old.name : clean;
        place.icon = meta != null ? meta.icon : old != null ? old.icon : "flag";
        place.color = meta != null ? meta.color : old != null ? old.color : -1;
        place.note = meta != null ? meta.note : old != null ? old.note : "";
        place.vis = meta != null ? meta.vis : old != null ? old.vis : PlaceBook.Vis.ALL;
        place.by = old != null && old.by != null ? old.by : p.getUUID();
        place.byName = old != null && !old.byName.isEmpty() ? old.byName : p.getGameProfile().getName();
        place.at = System.currentTimeMillis();
        PlaceBook.Put put = book.setPlace(place);
        store(p).touch();
        PlacesServer.changedAll(p.server);
        PlacesServer.log(p, (put == PlaceBook.Put.ADDED ? "поставил" : "переставил") + " место сервера " + place.name);
        return Reply.ok(Component.translatable(put == PlaceBook.Put.ADDED ? "rysmap.place.place_set" : "rysmap.place.place_moved", place.name));
    }

    /** Изменить место сервера: имя, значок, цвет, описание, кто видит. Место остаётся где было. */
    public static Reply editWarp(ServerPlayer p, String name, PlaceBook.Place meta) {
        if (!PlacesServer.PLACE_MANAGE.test(p)) return Reply.fail(Component.translatable("rysmap.place.manage_perm"));
        PlaceBook book = store(p).book();
        PlaceBook.Place old = book.place(name);
        if (old == null) return Reply.fail(Component.translatable("rysmap.place.no_place", name));
        String to = PlaceNames.clean(meta.name);
        if (!to.isEmpty() && !PlaceNames.key(to).equals(PlaceNames.key(old.name)) && !book.renamePlace(old.name, to))
            return Reply.fail(Component.translatable("rysmap.place.name_taken", to));
        PlaceBook.Place cur = book.place(to.isEmpty() ? old.name : to);
        if (cur == null) return Reply.fail(Component.translatable("rysmap.place.no_place", name));
        PlaceBook.Place upd = cur.copy();
        upd.icon = meta.icon;
        upd.color = meta.color;
        upd.note = meta.note == null ? "" : meta.note.strip();
        if (upd.note.length() > 200) upd.note = upd.note.substring(0, 200);
        upd.vis = meta.vis;
        book.setPlace(upd);
        store(p).touch();
        PlacesServer.changedAll(p.server);
        PlacesServer.log(p, "изменил место сервера " + upd.name);
        return Reply.ok(Component.translatable("rysmap.place.place_edited", upd.name));
    }

    public static Reply delWarp(ServerPlayer p, String name) {
        if (!PlacesServer.PLACE_MANAGE.test(p)) return Reply.fail(Component.translatable("rysmap.place.manage_perm"));
        PlaceBook.Place w = store(p).book().place(name);
        if (w == null) return Reply.fail(Component.translatable("rysmap.place.no_place", name));
        store(p).book().removePlace(w.name);
        store(p).touch();
        PlacesServer.changedAll(p.server);
        PlacesServer.log(p, "удалил место сервера " + w.name);
        return Reply.ok(Component.translatable("rysmap.place.place_removed", w.name));
    }

    // ---- назад ----

    /** К записи истории «назад» ({@code index} 0 — последняя). Место гибели можно всегда, прочие по привату. */
    public static Reply back(ServerPlayer p, int index) {
        PlaceBook book = store(p).book();
        List<PlaceBook.Back> all = book.back(p.getUUID());
        if (all.isEmpty()) return Reply.fail(Component.translatable("rysmap.place.no_back"));
        if (index < 0 || index >= all.size()) return Reply.fail(Component.translatable("rysmap.place.no_back"));
        PlaceBook.Back b = all.get(index);
        if (dim(b.dim) == null) return Reply.fail(Component.translatable("rysmap.place.bad_dimension", b.dim));
        TravelApi.Spot s = TravelApi.Spot.of(ResourceLocation.parse(b.dim), b.x, b.y, b.z);
        if (b.facing) s = s.facing(b.yRot, b.xRot);
        Component label = Component.translatable(b.death() ? "rysmap.place.death" : "rysmap.place.back");
        TravelApi.Trip trip = TravelApi.Trip.to(s).reason(b.death() ? TravelApi.Reason.DEATH : TravelApi.Reason.BACK).label(label).noBack().build();
        UUID id = p.getUUID();
        Travel.Res r = Travel.INSTANCE.go(p, trip, false, (ok, why, code) -> {
            ServerPlayer now = p.server.getPlayerList().getPlayer(id);
            if (ok) {
                // снять запись, если она ещё в истории (по самому объекту: индекс мог сдвинуться)
                List<PlaceBook.Back> cur = book.back(id);
                for (int i = 0; i < cur.size(); i++) {
                    if (cur.get(i) == b) {
                        book.removeBack(id, i);
                        store(p).touch();
                        break;
                    }
                }
                if (now != null) PlacesServer.changed(now);
            } else if (now != null && !code.equals("hurt") && !code.equals("moved")) {
                // сорванный разогрев уже показан строкой разогрева
                PlacesServer.note(now, PlacesServer.Note.DENIED, why, 0, "back");
            }
        });
        return of(r.result(), label);
    }

    // ---- спавн, кровать, случайное место ----

    public static Reply spawn(ServerPlayer p) {
        ServerLevel level = p.server.overworld();
        BlockPos s = level.getSharedSpawnPos();
        Component label = Component.translatable("rysmap.place.spawn");
        TravelApi.Result r = Travel.INSTANCE.teleport(p, TravelApi.Trip.to(TravelApi.Spot.of(level, s.getX() + 0.5, s.getY(), s.getZ() + 0.5))
            .reason(TravelApi.Reason.SPAWN).label(label).build());
        return of(r, label);
    }

    public static Reply bed(ServerPlayer p) {
        BlockPos pos = p.getRespawnPosition();
        if (pos == null) return Reply.fail(Component.translatable("rysmap.place.no_bed"));
        ResourceKey<Level> dim = p.getRespawnDimension();
        Component label = Component.translatable("rysmap.place.bed");
        TravelApi.Result r = Travel.INSTANCE.teleport(p, TravelApi.Trip.to(TravelApi.Spot.of(dim, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5))
            .reason(TravelApi.Reason.BED).label(label).build());
        return of(r, label);
    }

    /** Случайное место: от и до блоков от спавна измерения, не на занятой земле, до 12 попыток. */
    public static Reply random(ServerPlayer p) {
        PlaceRules rules = MapServer.rules().places;
        ServerLevel level = p.serverLevel();
        String dimId = level.dimension().location().toString();
        Component label = Component.translatable("rysmap.place.random");
        if (rules.randomExcluded.contains(dimId)) return Reply.fail(Component.translatable("rysmap.place.random_excluded"));
        BlockPos center = level.getSharedSpawnPos();
        int min = rules.randomMin, max = rules.randomMax;
        var border = level.getWorldBorder();
        IntFunction<int[]> next = attempt -> {
            if (attempt >= 12) return null;
            for (int i = 0; i < 16; i++) {
                double angle = RANDOM.nextDouble() * Math.PI * 2;
                double dist = min + RANDOM.nextDouble() * (max - min);
                int x = center.getX() + (int) (Math.cos(angle) * dist), z = center.getZ() + (int) (Math.sin(angle) * dist);
                if (border.isWithinBounds(new BlockPos(x, 0, z))) return new int[]{x, z};
            }
            return null;
        };
        int[] first = next.apply(0);
        if (first == null) return Reply.fail(Component.translatable("rysmap.travel.no_random"));
        TravelApi.Trip trip = TravelApi.Trip.to(TravelApi.Spot.of(level, first[0] + 0.5, SafeSpot.NO_HINT, first[1] + 0.5))
            .reason(TravelApi.Reason.RANDOM).label(label).build();
        Travel.Res r = Travel.INSTANCE.go(p, trip, true, null, next);
        return of(r.result(), label);
    }
}

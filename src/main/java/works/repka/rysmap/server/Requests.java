package works.repka.rysmap.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.ryscore.team.TeamsApi;
import works.repka.ryscore.travel.TravelApi;
import works.repka.rysmap.net.PlaceNet;
import works.repka.rysmap.places.PlaceRules;

/**
 * Запросы к игроку (план Essentials 4.3, макет {@code 05}): «Попроситься к нему» ({@code /tpa}) и «Позвать к себе»
 * ({@code /tpahere}). Запрос живёт срок из правил (60 с), ответ кнопками уведомления и клавишами Y и N ядра; без «Мест» на
 * клиенте ответ строкой чата с кнопками. Согласие хозяина и есть разрешение: проверки привата нет (правило 4.4.3). Своим по
 * команде без запроса, если команда дала право {@code rysmap:team_tp}. Пауза функции у того, кто просил, ставится после
 * переноса. Только поток сервера.
 */
public final class Requests {
    /** Запрос: {@code from} просит {@code to}; {@code here} — позвать {@code to} к себе. */
    record Req(UUID from, UUID to, boolean here, long until) {}

    private static final List<Req> ALL = new ArrayList<>();

    private Requests() {}

    static void clear() {
        ALL.clear();
    }

    private static @Nullable Req find(UUID from, UUID to) {
        for (Req r : ALL) if (r.from.equals(from) && r.to.equals(to)) return r;
        return null;
    }

    /** Отправить запрос. */
    public static Places.Reply send(ServerPlayer from, ServerPlayer to, boolean here) {
        if (from == to || from.getUUID().equals(to.getUUID())) return Places.Reply.fail(Component.translatable("rysmap.request.self"));
        if (!Travel.allowed(from, PlaceRules.Fn.REQUEST)) return Places.Reply.fail(Component.translatable("rysmap.travel.off"));
        int left = Travel.INSTANCE.left(from, PlaceRules.Fn.REQUEST);
        if (left > 0) return Places.Reply.fail(Component.translatable("rysmap.travel.pause", Component.translatable("rysmap.request.label"),
            left / 60 + ":" + String.format("%02d", left % 60)));
        TeamsApi teams = TeamsApi.get();
        UUID team = teams.teamOf(from.getUUID());
        // свои по команде: без запроса, если команда дала право
        if (!teams.isSolo(team) && team.equals(teams.teamOf(to.getUUID())) && teams.can(from.getUUID(), PlacesServer.TEAM_TP_PERM)) {
            return move(from, to, here, true);
        }
        Req old = find(from.getUUID(), to.getUUID());
        if (old != null) ALL.remove(old);
        int timeout = MapServer.rules().places.requestTimeout;
        Req r = new Req(from.getUUID(), to.getUUID(), here, System.currentTimeMillis() + timeout * 1000L);
        ALL.add(r);
        show(from.server, r, timeout);
        return Places.Reply.ok(Component.translatable(here ? "rysmap.request.sent_here" : "rysmap.request.sent", to.getGameProfile().getName()));
    }

    /** Ответ на запрос: {@code fromName} null — последний пришедший. */
    public static Places.Reply answer(ServerPlayer to, @Nullable ServerPlayer from, boolean accept) {
        Req r = null;
        for (Req x : ALL) if (x.to.equals(to.getUUID()) && (from == null || x.from.equals(from.getUUID()))) r = x;
        if (r == null) return Places.Reply.fail(Component.translatable("rysmap.request.none"));
        ALL.remove(r);
        MinecraftServer server = to.server;
        ServerPlayer asker = server.getPlayerList().getPlayer(r.from);
        gone(server, r);
        if (asker == null) return Places.Reply.fail(Component.translatable("rysmap.request.offline"));
        if (!accept) {
            PlacesServer.note(asker, PlacesServer.Note.DENIED, Component.translatable("rysmap.request.declined", to.getGameProfile().getName()), 0, "request");
            return Places.Reply.ok(Component.translatable("rysmap.request.you_declined", asker.getGameProfile().getName()));
        }
        Places.Reply moved = move(asker, to, r.here, false);
        if (!moved.ok()) PlacesServer.note(asker, PlacesServer.Note.DENIED, moved.text(), 0, "request");
        return moved.ok() ? Places.Reply.silent() : moved;
    }

    /** Отменить свой запрос. */
    public static Places.Reply cancel(ServerPlayer from, @Nullable ServerPlayer to) {
        Req r = null;
        for (Req x : ALL) if (x.from.equals(from.getUUID()) && (to == null || x.to.equals(to.getUUID()))) r = x;
        if (r == null) return Places.Reply.fail(Component.translatable("rysmap.request.none_mine"));
        ALL.remove(r);
        gone(from.server, r);
        return Places.Reply.ok(Component.translatable("rysmap.request.cancelled"));
    }

    /** Перенос по принятому запросу (или своему по команде): кто позвал, к тому и переносят. */
    private static Places.Reply move(ServerPlayer asker, ServerPlayer other, boolean here, boolean team) {
        ServerPlayer mover = here ? other : asker, dest = here ? asker : other;
        TravelApi.Trip trip = TravelApi.Trip.to(TravelApi.Spot.of(dest.serverLevel(), dest.getX(), dest.getY(), dest.getZ()))
            .reason(TravelApi.Reason.REQUEST).label(Component.literal(dest.getGameProfile().getName())).skipClaims().skipCooldown().build();
        Travel.Res r = Travel.INSTANCE.go(mover, trip, false, null);
        if (!r.result().ok()) return Places.of(r.result(), Component.translatable("rysmap.request.label"));
        Travel.INSTANCE.mark(asker, PlaceRules.Fn.REQUEST.id);
        PlacesServer.changed(asker);
        if (team) return Places.Reply.ok(Component.translatable(here ? "rysmap.request.team_here" : "rysmap.request.team_to", other.getGameProfile().getName()));
        return Places.Reply.silent();
    }

    /** Раз в секунду: погасить истёкшие. */
    static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Req> it = ALL.iterator();
        List<Req> expired = new ArrayList<>();
        while (it.hasNext()) {
            Req r = it.next();
            boolean online = server.getPlayerList().getPlayer(r.from) != null && server.getPlayerList().getPlayer(r.to) != null;
            if (now >= r.until || !online) {
                it.remove();
                expired.add(r);
            }
        }
        for (Req r : expired) {
            gone(server, r);
            ServerPlayer from = server.getPlayerList().getPlayer(r.from);
            ServerPlayer to = server.getPlayerList().getPlayer(r.to);
            if (from != null && to != null && now >= r.until)
                PlacesServer.note(from, PlacesServer.Note.DENIED, Component.translatable("rysmap.request.expired", to.getGameProfile().getName()), 0, "request");
        }
    }

    /** Запросы игрока для меню и колеса: кто ждёт его ответа. */
    static List<Req> incoming(UUID to) {
        List<Req> out = new ArrayList<>();
        for (Req r : ALL) if (r.to.equals(to)) out.add(r);
        return out;
    }

    /** Где окажется тот, кого перенесут: измерение и чья земля. */
    static String where(ServerPlayer dest) {
        String dim = dest.level().dimension().location().toString();
        UUID owner = ClaimsApi.get().ownerAt(dest.level().dimension(), dest.getBlockX() >> 4, dest.getBlockZ() >> 4);
        return owner == null ? dim : dim + "|" + TeamsApi.get().name(owner).getString();
    }

    private static void show(MinecraftServer server, Req r, int seconds) {
        ServerPlayer from = server.getPlayerList().getPlayer(r.from), to = server.getPlayerList().getPlayer(r.to);
        if (from == null || to == null) return;
        String where = where(r.here ? from : to);
        String fromName = from.getGameProfile().getName(), toName = to.getGameProfile().getName();
        if (PlaceNet.canSend(to, PlaceNet.Request.TYPE)) {
            PlacesServer.send(to, new PlaceNet.Request(r.from, fromName, false, r.here, where, seconds));
        } else {
            MutableComponent line = Component.translatable(r.here ? "rysmap.request.chat_here" : "rysmap.request.chat_to", fromName).append(" ");
            line.append(button("rysmap.request.accept", PlacesServer.cmd("tpaccept " + fromName), 0x55FF55)).append(" ");
            line.append(button("rysmap.request.decline", PlacesServer.cmd("tpdeny " + fromName), 0xFF5555));
            to.sendSystemMessage(line);
        }
        if (PlaceNet.canSend(from, PlaceNet.Request.TYPE)) PlacesServer.send(from, new PlaceNet.Request(r.to, toName, true, r.here, where, seconds));
    }

    private static Component button(String key, String command, int color) {
        return Component.literal("[").append(Component.translatable(key)).append("]").withStyle(s -> s.withColor(color).withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static void gone(MinecraftServer server, Req r) {
        ServerPlayer from = server.getPlayerList().getPlayer(r.from), to = server.getPlayerList().getPlayer(r.to);
        if (to != null && from != null && PlaceNet.canSend(to, PlaceNet.Request.TYPE))
            PlacesServer.send(to, new PlaceNet.Request(r.from, from.getGameProfile().getName(), false, r.here, "", 0));
        else if (to != null && PlaceNet.canSend(to, PlaceNet.Request.TYPE))
            PlacesServer.send(to, new PlaceNet.Request(r.from, "", false, r.here, "", 0));
        if (from != null && PlaceNet.canSend(from, PlaceNet.Request.TYPE))
            PlacesServer.send(from, new PlaceNet.Request(r.to, to == null ? "" : to.getGameProfile().getName(), true, r.here, "", 0));
    }
}

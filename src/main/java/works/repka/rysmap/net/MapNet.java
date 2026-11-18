package works.repka.rysmap.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import works.repka.rysmap.RYSMap;

/**
 * Сеть карты (план 9.1). Канал необязательный: клиент с RYSMap входит на сервер без него (ключ мира тогда по адресу,
 * телепорта нет), а сервер с RYSMap пускает клиента без мода и ничего ему не шлёт. Каждый пакет договаривается отдельно:
 * сервер шлёт только то, что клиент умеет принять ({@code hasChannel}), поэтому клиент 0.1.0 без новых пакетов
 * по-прежнему получает {@link Hello}.
 *
 * <ul>
 *   <li>{@link Hello} сервер → клиент при входе: UUID мира, старый id FTB;</li>
 *   <li>{@link Rules} сервер → клиент: правила сервера и что можно этому игроку; при входе, смене правил и прав;</li>
 *   <li>{@link SetRules} клиент → сервер: оператор правит правила (сервер проверяет уровень 2);</li>
 *   <li>{@link Teleport} клиент → сервер: телепорт с карты; {@link TeleportResult} ответ;</li>
 *   <li>{@link Players} сервер → клиент раз в секунду: где игроки, которых этому игроку можно видеть.</li>
 * </ul>
 */
public final class MapNet {
    /** Версия формата пакета: растёт вместе с полями. */
    public static final int VERSION = 1;

    private MapNet() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, path);
    }

    /** Привет при входе: версия, UUID мира, старый id сервера FTB (пусто, если его нет). */
    public record Hello(int version, UUID world, String ftbId) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Hello::version, UUIDUtil.STREAM_CODEC, Hello::world, ByteBufCodecs.stringUtf8(64), Hello::ftbId, Hello::new).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Правила сервера для этого игрока. {@code teleport}: 0 выключен, 1 операторы, 2 все. {@code op}: игрок может править
     * правила; {@code canTeleport}: правило пускает этого игрока; {@code pauseLeft}: сколько секунд паузы осталось.
     */
    public record Rules(int teleport, int pause, boolean minimap, boolean caves, boolean players, boolean mobs,
                        boolean op, boolean canTeleport, int pauseLeft, boolean team, int playersMode) implements CustomPacketPayload {
        public static final Type<Rules> TYPE = new Type<>(id("rules"));
        public static final StreamCodec<FriendlyByteBuf, Rules> CODEC = CustomPacketPayload.codec(Rules::write, Rules::read);

        private void write(FriendlyByteBuf b) {
            b.writeVarInt(teleport);
            b.writeVarInt(pause);
            // бит 64 «в команде» (0.1.0-m8): клиент 0.1.0-m7 его не читает
            int flags = (minimap ? 1 : 0) | (caves ? 2 : 0) | (players ? 4 : 0) | (mobs ? 8 : 0) | (op ? 16 : 0) | (canTeleport ? 32 : 0) | (team ? 64 : 0)
                // режим чужих игроков (0.1.0-m10): номер + 1 в битах 7–9, 0 у старого сервера
                | ((playersMode + 1) & 7) << 7;
            b.writeVarInt(flags);
            b.writeVarInt(pauseLeft);
        }

        private static Rules read(FriendlyByteBuf b) {
            int teleport = b.readVarInt(), pause = b.readVarInt(), f = b.readVarInt(), left = b.readVarInt();
            int mode = ((f >> 7) & 7) - 1;
            if (mode < 0) mode = (f & 4) != 0 ? 3 : 0; // старый сервер: флажок «все»
            return new Rules(teleport, pause, (f & 1) != 0, (f & 2) != 0, (f & 4) != 0, (f & 8) != 0, (f & 16) != 0, (f & 32) != 0, left, (f & 64) != 0, mode);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Оператор меняет правила. */
    public record SetRules(int teleport, int pause, boolean minimap, boolean caves, boolean players, boolean mobs, int playersMode) implements CustomPacketPayload {
        public static final Type<SetRules> TYPE = new Type<>(id("set_rules"));
        public static final StreamCodec<FriendlyByteBuf, SetRules> CODEC = CustomPacketPayload.codec(SetRules::write, SetRules::read);

        private void write(FriendlyByteBuf b) {
            b.writeVarInt(teleport);
            b.writeVarInt(pause);
            b.writeVarInt((minimap ? 1 : 0) | (caves ? 2 : 0) | (players ? 4 : 0) | (mobs ? 8 : 0) | ((playersMode + 1) & 7) << 4);
        }

        private static SetRules read(FriendlyByteBuf b) {
            int teleport = b.readVarInt(), pause = b.readVarInt(), f = b.readVarInt();
            int mode = ((f >> 4) & 7) - 1;
            if (mode < 0) mode = (f & 4) != 0 ? 1 : 0; // старый клиент: флажок
            return new SetRules(teleport, pause, (f & 1) != 0, (f & 2) != 0, (f & 4) != 0, (f & 8) != 0, mode);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Телепорт с карты: измерение, столбец и высота-подсказка ({@code Integer.MIN_VALUE}: не знаю). */
    public record Teleport(String dimension, int x, int y, int z) implements CustomPacketPayload {
        public static final Type<Teleport> TYPE = new Type<>(id("teleport"));
        public static final StreamCodec<FriendlyByteBuf, Teleport> CODEC = CustomPacketPayload.codec(Teleport::write, Teleport::read);

        private void write(FriendlyByteBuf b) {
            b.writeUtf(dimension, 256);
            b.writeInt(x);
            b.writeInt(y);
            b.writeInt(z);
        }

        private static Teleport read(FriendlyByteBuf b) {
            return new Teleport(b.readUtf(256), b.readInt(), b.readInt(), b.readInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Ответ на телепорт. {@code reason}: пусто при успехе, иначе последняя часть ключа {@code rysmap.tp.<reason>};
     * {@code seconds}: при отказе по паузе сколько осталось, при успехе длина паузы после него.
     */
    public record TeleportResult(boolean ok, String reason, int seconds) implements CustomPacketPayload {
        public static final Type<TeleportResult> TYPE = new Type<>(id("teleport_result"));
        public static final StreamCodec<FriendlyByteBuf, TeleportResult> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, TeleportResult::ok, ByteBufCodecs.stringUtf8(64), TeleportResult::reason, ByteBufCodecs.VAR_INT, TeleportResult::seconds,
            TeleportResult::new).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Пинг команде с карты (Т2): место и вид («here»). */
    public record Ping(String dimension, int x, int y, int z, String kind) implements CustomPacketPayload {
        public static final Type<Ping> TYPE = new Type<>(id("ping"));
        public static final StreamCodec<FriendlyByteBuf, Ping> CODEC = CustomPacketPayload.codec(Ping::write, Ping::read);

        private void write(FriendlyByteBuf b) {
            b.writeUtf(dimension, 256);
            b.writeInt(x);
            b.writeInt(y);
            b.writeInt(z);
            b.writeUtf(kind, 32);
        }

        private static Ping read(FriendlyByteBuf b) {
            return new Ping(b.readUtf(256), b.readInt(), b.readInt(), b.readInt(), b.readUtf(32));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Пинг участника команды или союзника: метка на карте на минуту. */
    public record PingShow(UUID by, String name, String dimension, int x, int y, int z, String kind, int color) implements CustomPacketPayload {
        public static final Type<PingShow> TYPE = new Type<>(id("ping_show"));
        public static final StreamCodec<FriendlyByteBuf, PingShow> CODEC = CustomPacketPayload.codec(PingShow::write, PingShow::read);

        private void write(FriendlyByteBuf b) {
            b.writeUUID(by);
            b.writeUtf(name, 32);
            b.writeUtf(dimension, 256);
            b.writeInt(x);
            b.writeInt(y);
            b.writeInt(z);
            b.writeUtf(kind, 32);
            b.writeInt(color);
        }

        private static PingShow read(FriendlyByteBuf b) {
            return new PingShow(b.readUUID(), b.readUtf(32), b.readUtf(256), b.readInt(), b.readInt(), b.readInt(), b.readUtf(32), b.readInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Один игрок в {@link Players}. {@code team}: из команды получателя. */
    public record PlayerSpot(UUID id, String name, String dimension, int x, int y, int z, boolean team) {}

    /** Где игроки, которых получателю можно видеть (себя нет). */
    public record Players(List<PlayerSpot> players) implements CustomPacketPayload {
        public static final Type<Players> TYPE = new Type<>(id("players"));
        public static final StreamCodec<FriendlyByteBuf, Players> CODEC = CustomPacketPayload.codec(Players::write, Players::read);
        private static final int MAX = 512;

        private void write(FriendlyByteBuf b) {
            int n = Math.min(MAX, players.size());
            b.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                PlayerSpot p = players.get(i);
                b.writeUUID(p.id());
                b.writeUtf(p.name(), 32);
                b.writeUtf(p.dimension(), 256);
                b.writeVarInt(p.x());
                b.writeVarInt(p.y());
                b.writeVarInt(p.z());
                b.writeBoolean(p.team());
            }
        }

        private static Players read(FriendlyByteBuf b) {
            int n = Math.min(MAX, b.readVarInt());
            List<PlayerSpot> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                out.add(new PlayerSpot(b.readUUID(), b.readUtf(32), b.readUtf(256), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()));
            return new Players(out);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1").optional();
        r.playToClient(Hello.TYPE, Hello.CODEC, MapNet::onHello);
        r.playToClient(Rules.TYPE, Rules.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.rules(p);
        });
        r.playToClient(TeleportResult.TYPE, TeleportResult.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.teleported(p);
        });
        r.playToClient(Players.TYPE, Players.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.players(p);
        });
        r.playToClient(PingShow.TYPE, PingShow.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.ping(p);
        });
        r.playToServer(Ping.TYPE, Ping.CODEC, (p, c) -> {
            if (c.player() instanceof ServerPlayer sp) works.repka.rysmap.server.TeamPings.request(sp, p);
        });
        r.playToServer(SetRules.TYPE, SetRules.CODEC, (p, c) -> {
            if (c.player() instanceof ServerPlayer sp) works.repka.rysmap.server.MapServer.setRules(sp, p);
        });
        r.playToServer(Teleport.TYPE, Teleport.CODEC, (p, c) -> {
            if (c.player() instanceof ServerPlayer sp) works.repka.rysmap.server.Teleports.request(sp, p);
        });
        PlaceNet.register(r);
    }

    private static void onHello(Hello hello, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) Client.hello(hello);
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не загружаются. */
    private static final class Client {
        static void hello(Hello hello) {
            works.repka.rysmap.client.ClientEntry.hello(hello.world(), hello.ftbId());
        }

        static void rules(Rules rules) {
            works.repka.rysmap.client.ServerRules.accept(rules);
        }

        static void teleported(TeleportResult result) {
            works.repka.rysmap.client.ServerRules.teleported(result);
        }

        static void players(Players players) {
            works.repka.rysmap.client.ServerRules.players(players);
        }

        static void ping(PingShow ping) {
            works.repka.rysmap.client.PingsLayer.add(ping);
        }
    }

    public static void sendHello(ServerPlayer player, Hello hello) {
        send(player, hello);
    }

    /** Игроку, если его клиент знает этот пакет. */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (!player.connection.hasChannel(payload.type())) return;
        PacketDistributor.sendToPlayer(player, payload);
    }
}

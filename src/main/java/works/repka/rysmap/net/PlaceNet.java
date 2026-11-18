package works.repka.rysmap.net;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import works.repka.rysmap.RYSMap;

/**
 * Сеть «Мест» (0.2.0-p1, план Essentials 4). Каждый пакет договаривается отдельно, как остальные пакеты карты: клиент без
 * «Мест» получает ответы строками чата.
 *
 * <ul>
 *   <li>{@link Sync} сервер → клиент: свои дома, дом команды, видимые места сервера, «назад», правила и паузы (JSON);</li>
 *   <li>{@link Note} сервер → клиент: отказ, разогрев, итог правки строкой уведомления;</li>
 *   <li>{@link Request} сервер → клиент: запрос к игроку пришёл, ждёт ответа или погас;</li>
 *   <li>{@link Op} клиент → сервер: действие с места (телепорт, поставить, удалить, запрос, ответ), JSON.</li>
 * </ul>
 */
public final class PlaceNet {
    private PlaceNet() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, path);
    }

    /** Места игрока и правила, JSON (формат в {@code server.PlacesServer#sync}). */
    public record Sync(String json) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(id("places"));
        public static final StreamCodec<FriendlyByteBuf, Sync> CODEC = CustomPacketPayload.codec((s, b) -> b.writeUtf(s.json, 1 << 20),
            b -> new Sync(b.readUtf(1 << 20)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Строка уведомления. {@code kind}: 0 отказ, 1 разогрев начался ({@code seconds}), 2 разогрев кончился или сорван
     * (текст, если сорван), 3 сделано (дом поставлен и прочее). {@code reason}: id причины перемещения для значка.
     */
    public record Note(int kind, Component text, int seconds, String reason) implements CustomPacketPayload {
        public static final Type<Note> TYPE = new Type<>(id("note"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Note> CODEC = CustomPacketPayload.codec(Note::write, Note::read);

        private void write(RegistryFriendlyByteBuf b) {
            b.writeVarInt(kind);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(b, text);
            b.writeVarInt(seconds);
            b.writeUtf(reason, 64);
        }

        private static Note read(RegistryFriendlyByteBuf b) {
            return new Note(b.readVarInt(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(b), b.readVarInt(), b.readUtf(64));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Запрос к игроку. {@code mine}: свой запрос (ждём ответа), иначе входящий. {@code here}: «Позвать к себе» (кто
     * позвал, к тому и перенесут). {@code where}: измерение и чья земля у того, к кому перенесут. {@code seconds}: сколько
     * осталось, 0 — запрос погас (принят, отклонён, отменён, истёк).
     */
    public record Request(UUID other, String name, boolean mine, boolean here, String where, int seconds) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(id("request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(Request::write, Request::read);

        private void write(FriendlyByteBuf b) {
            b.writeUUID(other);
            b.writeUtf(name, 64);
            b.writeBoolean(mine);
            b.writeBoolean(here);
            b.writeUtf(where, 256);
            b.writeVarInt(seconds);
        }

        private static Request read(FriendlyByteBuf b) {
            return new Request(b.readUUID(), b.readUtf(64), b.readBoolean(), b.readBoolean(), b.readUtf(256), b.readVarInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Действие с клиента: имя действия и данные JSON. */
    public record Op(String op, String json) implements CustomPacketPayload {
        public static final Type<Op> TYPE = new Type<>(id("place_op"));
        public static final StreamCodec<FriendlyByteBuf, Op> CODEC = CustomPacketPayload.codec((o, b) -> {
            b.writeUtf(o.op, 32);
            b.writeUtf(o.json, 8192);
        }, b -> new Op(b.readUtf(32), b.readUtf(8192)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void register(PayloadRegistrar r) {
        r.playToClient(Sync.TYPE, Sync.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.sync(p);
        });
        r.playToClient(Note.TYPE, Note.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.note(p);
        });
        r.playToClient(Request.TYPE, Request.CODEC, (p, c) -> {
            if (FMLEnvironment.dist.isClient()) Client.request(p);
        });
        r.playToServer(Op.TYPE, Op.CODEC, (p, c) -> {
            if (c.player() instanceof ServerPlayer sp) works.repka.rysmap.server.PlacesServer.op(sp, p);
        });
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не загружаются. */
    private static final class Client {
        static void sync(Sync s) {
            works.repka.rysmap.client.PlacesClient.sync(s.json());
        }

        static void note(Note n) {
            works.repka.rysmap.client.PlacesClient.note(n);
        }

        static void request(Request r) {
            works.repka.rysmap.client.PlacesClient.request(r);
        }
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type);
    }
}

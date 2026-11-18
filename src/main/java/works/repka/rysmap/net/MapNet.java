package works.repka.rysmap.net;

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
 * Сеть карты. В 0.1 один пакет {@link Hello} сервер → клиент. Канал необязательный: клиент с RYSMap входит на сервер
 * без него (ключ мира тогда по адресу), а сервер с RYSMap пускает клиента без мода и ничего ему не шлёт.
 */
public final class MapNet {
    /** Версия формата пакета: растёт вместе с полями. */
    public static final int VERSION = 1;

    private MapNet() {}

    /** Привет при входе: версия, UUID мира, старый id сервера FTB (пусто, если его нет). */
    public record Hello(int version, UUID world, String ftbId) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Hello::version, UUIDUtil.STREAM_CODEC, Hello::world, ByteBufCodecs.stringUtf8(64), Hello::ftbId, Hello::new).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(Hello.TYPE, Hello.CODEC, MapNet::onHello);
    }

    private static void onHello(Hello hello, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) Client.hello(hello);
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не загружаются. */
    private static final class Client {
        static void hello(Hello hello) {
            works.repka.rysmap.client.ClientEntry.hello(hello.world(), hello.ftbId());
        }
    }

    public static void sendHello(ServerPlayer player, Hello hello) {
        if (!player.connection.hasChannel(Hello.TYPE)) return;
        PacketDistributor.sendToPlayer(player, hello);
    }
}

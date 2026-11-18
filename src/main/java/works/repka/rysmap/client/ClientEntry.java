package works.repka.rysmap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.rysmap.api.MapApiImpl;
import works.repka.rysmap.compat.SablePlots;
import works.repka.rysmap.tile.ColorTable;
import works.repka.rysmap.world.MapStore;

/** Клиентская часть: сессия карты на вход в мир, события чанков, тик, кадр, строка F3, реализация {@link MapApi}. */
public final class ClientEntry {
    private static @Nullable MapSession session;
    private static ClientConfig config = new ClientConfig();

    private ClientEntry() {}

    public static void init(IEventBus modBus, ModContainer container) {
        config = ClientConfig.load();
        SablePlots.setActive(ModList.get().isLoaded("sable"));
        MapApi.provide(new MapApiImpl());
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
            e.registerReloadListener((ResourceManagerReloadListener) resources -> ColorTable.INSTANCE.invalidate()));
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            if (e.getPlayer().level() instanceof ClientLevel level) ensure(level);
        });
        bus.addListener((ClientPlayerNetworkEvent.Clone e) -> {
            if (e.getNewPlayer().level() instanceof ClientLevel level) ensure(level);
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> close());
        bus.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ClientLevel level) ensure(level).chunkLoaded(e.getChunk().getPos().x, e.getChunk().getPos().z);
        });
        bus.addListener((ChunkEvent.Unload e) -> {
            if (session != null && e.getLevel() instanceof ClientLevel) session.chunkUnloaded(e.getChunk().getPos().x, e.getChunk().getPos().z);
        });
        bus.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (session != null && mc.level == null) {
                close();
                return;
            }
            if (session != null && mc.level != null) {
                ensure(mc.level);
                session.tick(mc);
            }
        });
        bus.addListener((RenderFrameEvent.Pre e) -> {
            if (session != null) session.frame();
        });
        bus.addListener((CustomizeGuiOverlayEvent.DebugText e) -> {
            if (session != null && Minecraft.getInstance().getDebugOverlay().showDebugScreen()) e.getRight().add(session.debugLine());
        });
    }

    /** Сессия текущего мира; создаётся при первом событии мира, измерение сверяется при каждом вызове. */
    private static MapSession ensure(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (session == null) {
            ColorTable.INSTANCE.setUnknown(unknownColor());
            session = new MapSession(new MapStore(mc.gameDirectory.toPath().resolve("rysmap"), MapSession.keyFor(mc)), config);
        }
        String dim = level.dimension().location().toString();
        if (!dim.equals(session.dimension())) {
            ColorTable.INSTANCE.biomes(level.registryAccess());
            session.enterDimension(level);
        }
        return session;
    }

    private static void close() {
        MapSession s = session;
        session = null;
        if (s != null) s.close();
    }

    public static @Nullable MapSession session() {
        return session;
    }

    public static ClientConfig config() {
        return config;
    }

    /** Полотно карты тёмное в любой теме: цвет неизвестного блока берётся из slate, если она есть. */
    private static int unknownColor() {
        Theme slate = null;
        for (Theme t : ThemeManager.all()) if (t.id.equals("ryscore:slate")) slate = t;
        return (slate != null ? slate : ThemeManager.get()).c("map.unknown") | 0xFF000000;
    }

    /** Из перехвата правок блоков. */
    public static void blockChanged(ClientLevel level, BlockPos pos) {
        MapSession s = session;
        if (s != null && level.dimension().location().toString().equals(s.dimension())) s.blockChanged(level, pos);
    }
}

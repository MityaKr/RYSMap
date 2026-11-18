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
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.dock.DockButton;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.layer.Layers;
import works.repka.rysmap.screen.MapScreen;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.rysmap.api.MapApiImpl;
import works.repka.rysmap.compat.SablePlots;
import works.repka.rysmap.tile.ColorTable;
import works.repka.rysmap.world.MapStore;

/** Клиентская часть: сессия карты на вход в мир, события чанков, тик, кадр, строка F3, реализация {@link MapApi}. */
public final class ClientEntry {
    public static final String CATEGORY = "key.categories.rysmap";
    /** Окно карты: M, как у FTB Chunks (в RepYS на M ещё магнит EnderIO, план 12, «Клавиши»). */
    public static final KeyMapping OPEN = new KeyMapping("key.rysmap.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping ZOOM_IN = new KeyMapping("key.rysmap.zoom_in", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, CATEGORY);
    public static final KeyMapping ZOOM_OUT = new KeyMapping("key.rysmap.zoom_out", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, CATEGORY);
    private static @Nullable MapSession session;
    private static ClientConfig config = new ClientConfig();

    private ClientEntry() {}

    public static void init(IEventBus modBus, ModContainer container) {
        config = ClientConfig.load();
        SablePlots.setActive(ModList.get().isLoaded("sable"));
        MapApi.provide(new MapApiImpl());
        works.repka.rysmap.point.PointsLayer.register();
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(OPEN);
            e.register(ZOOM_IN);
            e.register(ZOOM_OUT);
        });
        modBus.addListener((RegisterGuiLayersEvent e) ->
            e.registerBelow(VanillaGuiLayers.CHAT, ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "minimap"), Minimap::render));
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> DockButton.builder("rysmap_map")
            .label(Component.translatable("rysmap.dock")).description(() -> Component.translatable("rysmap.dock.description"))
            .tile(0xFF4FA3D9).icon(Icons.Shape.MAP).key(OPEN).order(20).replacesFtb("ftbchunks_chunks").action(MapScreen::open).register()));
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
                while (OPEN.consumeClick()) if (mc.screen == null) MapScreen.open();
                while (ZOOM_IN.consumeClick()) Minimap.zoom(1);
                while (ZOOM_OUT.consumeClick()) Minimap.zoom(-1);
            }
        });
        bus.addListener((net.neoforged.neoforge.client.event.ScreenEvent.Opening e) -> {
            if (e.getNewScreen() instanceof net.minecraft.client.gui.screens.DeathScreen && !(e.getCurrentScreen() instanceof net.minecraft.client.gui.screens.DeathScreen))
                works.repka.rysmap.point.PointsLayer.died();
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
        Layers.clear();
        if (MapApi.get() instanceof MapApiImpl api) api.track(null);
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

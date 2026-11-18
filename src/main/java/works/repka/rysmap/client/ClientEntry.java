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
    /** Окно новой точки (макет 09): по умолчанию без клавиши, в RepYS J занята Mekanism (план 12, «Клавиши»). */
    public static final KeyMapping NEW_POINT = new KeyMapping("key.rysmap.new_point", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    public static final KeyMapping ZOOM_OUT = new KeyMapping("key.rysmap.zoom_out", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, CATEGORY);
    private static @Nullable MapSession session;
    private static ClientConfig config = new ClientConfig();
    /** Что прислал сервер в MapHello этого входа: UUID мира и старый id FTB. */
    private static java.util.@Nullable UUID helloWorld;
    private static String helloFtb = "";
    private static boolean migrationStarted;

    private ClientEntry() {}

    public static void init(IEventBus modBus, ModContainer container) {
        config = ClientConfig.load();
        SablePlots.setActive(ModList.get().isLoaded("sable"));
        MapApi.provide(new MapApiImpl());
        works.repka.rysmap.point.PointsLayer.register();
        works.repka.ryscore.migrate.Migrations.register(new works.repka.rysmap.migrate.FtbMapStep());
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(OPEN);
            e.register(ZOOM_IN);
            e.register(ZOOM_OUT);
            e.register(NEW_POINT);
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
            session = new MapSession(new MapStore(maps(), MapSession.keyFor(mc, helloWorld)), config);
        }
        String dim = level.dimension().location().toString();
        if (!dim.equals(session.dimension())) {
            ColorTable.INSTANCE.biomes(level.registryAccess());
            session.enterDimension(level);
        }
        return session;
    }

    private static java.nio.file.Path maps() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("rysmap");
    }

    /**
     * MapHello от сервера: папка карты встаёт на UUID мира. Если сессия уже открыта под старым ключом (адрес или имя
     * сохранения), она закрывается, её папка переезжает на новый ключ, новая сессия снова берёт загруженные чанки.
     * Затем, если сервер знает старый id FTB, запускается шаг переезда {@code map} (флаг и отметку проверяет ядро).
     */
    public static void hello(java.util.UUID world, String ftbId) {
        helloWorld = world;
        helloFtb = ftbId == null ? "" : ftbId;
        Minecraft mc = Minecraft.getInstance();
        RYSMap.LOG.info("[rysmap] MapHello: мир {}, id FTB {}", world, helloFtb.isEmpty() ? "нет" : helloFtb);
        if (mc.level == null) return;
        works.repka.rysmap.world.WorldKey want0 = MapSession.keyFor(mc, world);
        try {
            MapStore.remember(maps(), MapSession.fallbackKey(mc).id(), want0.id());
        } catch (java.io.IOException e) {
            RYSMap.LOG.warn("[rysmap] keys.json не записался: {}", e.toString());
        }
        MapSession s = session;
        if (s == null) {
            ensure(mc.level);
        } else {
            works.repka.rysmap.world.WorldKey want = MapSession.keyFor(mc, world);
            if (!s.store.key().id().equals(want.id())) rekey(mc, s, want);
        }
        startMigration(mc);
    }

    private static void rekey(Minecraft mc, MapSession old, works.repka.rysmap.world.WorldKey want) {
        session = null;
        old.close();
        works.repka.rysmap.world.WorldKey key = want;
        try {
            RYSMap.LOG.info("[rysmap] ключ мира {} → {}: {}", old.store.key().id(), want.id(), MapStore.adopt(maps(), old.store.key(), want));
        } catch (java.io.IOException e) {
            // папка занята или нет прав: карта остаётся в старой папке, попытка повторится при следующем входе
            RYSMap.LOG.warn("[rysmap] папка карты не переехала на {}: {}", want.id(), e.toString());
            key = new works.repka.rysmap.world.WorldKey(old.store.key().id(), want.name());
        }
        session = new MapSession(new MapStore(maps(), key), config);
        if (mc.level != null && mc.player != null) {
            session.enterDimension(mc.level);
            session.requeueLoaded(mc.level, mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4, mc.options.getEffectiveRenderDistance() + 1);
        }
    }

    private static void startMigration(Minecraft mc) {
        MapSession s = session;
        if (migrationStarted || helloFtb.isEmpty() || s == null || mc.player == null) return;
        migrationStarted = true;
        works.repka.rysmap.migrate.FtbMapStep.host(new FtbHost(s, mc.player.level().dimension().location().toString(),
            mc.player.getBlockX(), mc.player.getBlockZ(), config.noMapDimensions));
        works.repka.ryscore.migrate.Migrations.runClient(mc.gameDirectory.toPath(), s.store.root(),
                java.util.Map.of(works.repka.rysmap.migrate.FtbMapStep.VALUE_FTB_ID, helloFtb))
            .whenComplete((r, t) -> {
                if (t != null) RYSMap.LOG.warn("[rysmap] переезд карты FTB не запустился", t);
            });
    }

    private static void close() {
        MapSession s = session;
        session = null;
        helloWorld = null;
        helloFtb = "";
        migrationStarted = false;
        works.repka.rysmap.migrate.FtbMapStep.host(null);
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

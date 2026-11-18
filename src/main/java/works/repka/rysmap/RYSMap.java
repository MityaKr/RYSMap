package works.repka.rysmap;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа RYSMap. Карта клиентская; на сервере (и во встроенном сервере одиночной игры) только {@code MapHello}:
 * UUID мира и старый id FTB для папки карты и переезда.
 */
@Mod(RYSMap.MODID)
public final class RYSMap {
    public static final String MODID = "rysmap";
    public static final Logger LOG = LoggerFactory.getLogger(RYSMap.class);

    public RYSMap(IEventBus modBus, ModContainer container) {
        modBus.addListener(works.repka.rysmap.net.MapNet::register);
        works.repka.rysmap.server.MapServer.init();
        if (FMLEnvironment.dist.isClient()) works.repka.rysmap.client.ClientEntry.init(modBus, container);
    }
}

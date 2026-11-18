package works.repka.rysmap;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа RYSMap. Карта клиентская: на сервере мод пока ничего не делает (серверная часть с {@code MapHello}
 * придёт на этапе М2).
 */
@Mod(RYSMap.MODID)
public final class RYSMap {
    public static final String MODID = "rysmap";
    public static final Logger LOG = LoggerFactory.getLogger(RYSMap.class);

    public RYSMap(IEventBus modBus, ModContainer container) {
        if (FMLEnvironment.dist.isClient()) works.repka.rysmap.client.ClientEntry.init(modBus, container);
    }
}

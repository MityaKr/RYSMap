package works.repka.rysmap.client;

import java.util.Collection;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.layer.Layers;
import works.repka.rysmap.tile.Baker;

/**
 * Слои подсветки «Высоты» и «Свет» (план карты, раздел 5 «Потом»; 0.2.3). Меток у них нет: глазок в списке слоёв меняет
 * то, как пекарь красит тайлы ({@link Baker#overlay}), и тайлы перепекаются. «Высоты»: цвет по высоте и горизонтали через
 * 8 блоков. «Свет»: блоки, которые светят сами (факелы, фонари, светокамень, лава), ярко, остальное приглушено. Оба по
 * умолчанию выключены и видны и на миникарте: тайлы у карты и миникарты общие.
 */
public final class OverlayLayers {
    private OverlayLayers() {}

    static final class Overlay implements MapApi.Layer {
        private final ResourceLocation id;
        private final String key;
        private final Icons.Shape icon;
        private final int order;

        Overlay(String path, Icons.Shape icon, int order) {
            this.id = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, path);
            this.key = "rysmap.layer." + path;
            this.icon = icon;
            this.order = order;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Component name() {
            return Component.translatable(key);
        }

        @Override
        public Icons.Shape icon() {
            return icon;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public boolean onByDefault() {
            return false;
        }

        @Override
        public long revision() {
            return 0;
        }

        @Override
        public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
            return List.of();
        }
    }

    static final Overlay HEIGHTS = new Overlay("heights", Icons.Shape.LAYERS, 61);
    static final Overlay LIGHT = new Overlay("light", Icons.Shape.SUN, 62);

    static void register() {
        MapApi.get().layer(HEIGHTS);
        MapApi.get().layer(LIGHT);
    }

    /** Биты для пекаря по видимости слоёв. */
    static int bits() {
        return (Layers.visible(HEIGHTS) ? Baker.HEIGHTS : 0) | (Layers.visible(LIGHT) ? Baker.LIGHT : 0);
    }
}

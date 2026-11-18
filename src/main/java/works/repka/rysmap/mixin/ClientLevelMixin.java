package works.repka.rysmap.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysmap.client.ClientEntry;

/**
 * Правки блоков на клиенте для карты. Только смена состояния: обновления блок-сущностей (ленты, воронки, машины)
 * приходят с тем же состоянием и чанк не пачкают.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void rysmap$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) ClientEntry.blockChanged((ClientLevel) (Object) this, pos);
    }
}

package dimblend.asyncsave;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(DimBlendAsyncSave.MODID)
public class DimBlendAsyncSave {
    public static final String MODID = "dimblend_asyncsave";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimBlendAsyncSave() {
        LOGGER.info("DimBlend AsyncSave loaded: autosave file writes (level.dat / player data) run on a background writer thread");
    }
}

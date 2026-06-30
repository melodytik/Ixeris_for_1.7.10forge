package me.decce.ixeris;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Main mod class for Forge 1.7.10.
 *
 * This class handles Forge's mod lifecycle events. The actual thread-split
 * logic is triggered by the ASM transformer (injected into Minecraft.run()),
 * not by this class. This class exists primarily for:
 *  - Displaying the mod in the mods list
 *  - Logging startup information
 */
@Mod(modid = Ixeris.MOD_ID, name = Ixeris.MOD_NAME, version = Ixeris.VERSION,
     acceptableRemoteVersions = "*")
public class IxerisMod {

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        System.out.println("[Ixeris] 1.7.10 — Off-thread event polling for LWJGL 2.x");
        System.out.println("[Ixeris] Moves Display.processMessages() to a separate thread");
        System.out.println("[Ixeris] to improve FPS when moving the mouse.");

        IxerisConfig config = IxerisConfig.load();
        if (config.enabled) {
            System.out.println("[Ixeris] ENABLED — thread split will activate when the game starts.");
        } else {
            System.out.println("[Ixeris] DISABLED in config — the mod will have no effect.");
        }
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // The thread split is handled by the ASM transformer, not here.
    }
}

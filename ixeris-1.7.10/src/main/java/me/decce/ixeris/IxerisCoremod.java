package me.decce.ixeris;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Coremod entry point for Forge 1.7.10.
 *
 * Registered via the manifest entry FMLCorePlugin in the jar's
 * META-INF/MANIFEST.MF. This class is loaded very early in the startup
 * sequence, before Minecraft classes are loaded, which allows our
 * IxerisTransformer to patch Minecraft, Mouse, Keyboard and Display.
 */
@IFMLLoadingPlugin.Name("Ixeris")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({"me.decce.ixeris."})
public class IxerisCoremod implements IFMLLoadingPlugin {

    static {
        System.out.println("[Ixeris] Coremod class initialised.");
    }

    public IxerisCoremod() {
        System.out.println("[Ixeris] Coremod instance created, registering transformer.");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "me.decce.ixeris.IxerisTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // No-op
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}

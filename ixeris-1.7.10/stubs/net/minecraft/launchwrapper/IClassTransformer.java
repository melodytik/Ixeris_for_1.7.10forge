package net.minecraft.launchwrapper;

/**
 * Compilation stub. At runtime, Forge's LaunchClassLoader provides the real interface.
 */
public interface IClassTransformer {
    byte[] transform(String name, String transformedName, byte[] bytes);
}

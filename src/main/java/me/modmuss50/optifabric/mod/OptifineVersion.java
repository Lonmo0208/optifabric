package me.modmuss50.optifabric.mod;

import me.modmuss50.optifabric.patcher.ASMUtils;
import net.fabricmc.loader.api.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class OptifineVersion {
    public static String version;
    public static String minecraftVersion;
    public static JarType jarType;

    public static Path findOptifineJar() throws IOException {
        final Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        final AtomicReference<Path> optifineJar = new AtomicReference<>();

        try (Stream<Path> mods = Files.list(modsDir)) {
            mods.forEach(mod -> {
                if (Files.isDirectory(mod)) {
                    return;
                }
                if (!mod.toString().endsWith(".zip") && !mod.toString().endsWith(".jar")) {
                    return;
                }
                JarType type = OptifineVersion.getJarType(mod);
                if (type.error) {
                    if (type != JarType.INCOMPATIBLE) {
                        throw new RuntimeException(String.format("an error occurred when trying to find the OptiFine jar: %s", type.name()));
                    }
                    return;
                }
                if (type == JarType.OPTIFINE_MOD || type == JarType.OPTIFINE_INSTALLER) {
                    if (optifineJar.get() != null) {
                        Optifabric.error = "found 2 or more OptiFine jars, please ensure you only have 1 copy of OptiFine in the mods folder!";
                        throw new RuntimeException("multiple OptiFine jars");
                    }
                    jarType = type;
                    optifineJar.set(mod);
                }
            });
        }

        if (optifineJar.get() != null) {
            return optifineJar.get();
        }

        Optifabric.error = "OptiFabric could not find the OptiFine jar in the mods folder.";
        throw new FileNotFoundException("could not find OptiFine jar");
    }

    private static JarType getJarType(Path file) {
        ClassNode classNode;
        try (JarFile jarFile = new JarFile(file.toFile())) {
            JarEntry jarEntry = jarFile.getJarEntry("Config.class");
            if (jarEntry == null) {
                jarEntry = jarFile.getJarEntry("VersionThread.class" /* optifine light and pre-1.3? */);
            }
            if (jarEntry == null) {
                jarEntry = jarFile.getJarEntry("net/optifine/Config.class" /* 1.13+ */);
            }
            System.out.println("jar entry: " + jarEntry);
            if (jarEntry == null) {
                return JarType.SOMETHING_ELSE;
            }
            classNode = ASMUtils.asClassNode(jarEntry, jarFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 更健壮的版本检测
        for (FieldNode fieldNode : classNode.fields) {
            if (fieldNode.name.equals("VERSION") && fieldNode.value instanceof String) {
                version = (String) fieldNode.value;
                System.out.println("Found OptiFine version: " + version);
            }
            if (fieldNode.name.equals("MC_VERSION") && fieldNode.value instanceof String) {
                minecraftVersion = (String) fieldNode.value;
                System.out.println("Found Minecraft version for OptiFine: " + minecraftVersion);
            }
        }

        // 如果从字段中没找到，尝试从文件名推断
        if (version == null || version.isEmpty()) {
            String fileName = file.getFileName().toString();
            if (fileName.contains("OptiFine")) {
                version = fileName.replace(".jar", "").replace("OptiFine_", "");
                System.out.println("Inferred OptiFine version from filename: " + version);
            }
        }

        if (version == null || version.isEmpty()) {
            return JarType.INCOMPATIBLE;
        }

        // 跳过版本检查，强制加载
        FabricLoader.getInstance().getModContainer("minecraft").ifPresent(minecraft -> {
            String currentMCVersion = minecraft.getMetadata().getVersion().getFriendlyString();
            System.out.println("Current Minecraft version: " + currentMCVersion);

            if (minecraftVersion != null && !minecraftVersion.isEmpty()) {
                if (!currentMCVersion.contains(minecraftVersion)) {
                    System.err.printf("WARNING: OptiFine version mismatch - OptiFine is for %s, but Minecraft is %s. Forcing load anyway.\n", minecraftVersion, currentMCVersion);
                }
            } else {
                System.err.printf("WARNING: Could not detect target Minecraft version for OptiFine %s. Forcing load on %s.\n", version, currentMCVersion);
                // 尝试从版本字符串中推断
                if (version.contains("1.8.9")) {
                    minecraftVersion = "1.8.9";
                } else if (version.contains("1.8.8")) {
                    minecraftVersion = "1.8.8";
                }
            }
        });

        boolean installer;
        try (ZipFile fs = new ZipFile(file.toFile())) {
            installer = fs.stream().anyMatch(entry -> entry.getName().startsWith("patch/"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return installer ? JarType.OPTIFINE_INSTALLER : JarType.OPTIFINE_MOD;
    }

    public enum JarType {
        OPTIFINE_MOD(false),
        OPTIFINE_INSTALLER(false),
        INCOMPATIBLE(true),
        SOMETHING_ELSE(false);

        final boolean error;

        JarType(boolean error) {
            this.error = error;
        }
    }
}
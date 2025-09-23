package me.modmuss50.optifabric.mod;

import net.fabricmc.api.*;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.include.com.google.gson.stream.JsonReader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Environment(EnvType.CLIENT)
public class Optifabric {
    public static String error = null;
    private static final Map<String, List<String>> excludedClasses = new HashMap<>();
    private static final Path excludeConfig = FabricLoader.getInstance().getConfigDir().resolve("optifabric-excluded-classes.json");

    static {
        try {
            if (Files.exists(excludeConfig)) {
                JsonReader reader = new JsonReader(new FileReader(excludeConfig.toFile()));
                reader.beginObject();
                while (reader.hasNext()) {
                    String version = reader.nextName();
                    List<String> classes = new ArrayList<>();
                    reader.beginArray();
                    while (reader.hasNext()) {
                        classes.add(reader.nextString());
                    }
                    excludedClasses.put(version, classes);
                }
                reader.close();
            }
        } catch (IOException ignored) {
        }

        // 添加对1.8.9特定字段访问问题的排除类
        if (!excludedClasses.containsKey("1.8.9_HD_U_M5")) {
            List<String> classes = new ArrayList<>();
            // 添加有字段访问问题的类到排除列表
            classes.add("net/optifine/entity/model/ModelAdapterPigZombie.class");
            classes.add("net/optifine/entity/model/ModelAdapterEndermite.class");
            classes.add("net/optifine/entity/model/ModelAdapterBoat.class");
            // 可以根据需要添加更多有问题的类
            excludedClasses.put("1.8.9_HD_U_M5", classes);
        }
    }

    public static boolean hasError() {
        return Optifabric.error != null;
    }

    public static List<String> getExcludedClasses() {
        String versionKey = OptifineVersion.version != null ? OptifineVersion.version : "default";
        return excludedClasses.getOrDefault(versionKey, Collections.emptyList());
    }
}
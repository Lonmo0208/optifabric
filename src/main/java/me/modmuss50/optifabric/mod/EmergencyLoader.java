package me.modmuss50.optifabric.mod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.fabricmc.loader.api.FabricLoader;

import com.chocohead.mm.api.ClassTinkerers;

public class EmergencyLoader {
    public static void loadCriticalClasses() {
        try {
            System.out.println("[OptiFabric] 启动应急类加载");

            File optifineJar = OptifineVersion.findOptifineJar();
            System.out.println("[OptiFabric] 找到OptiFine jar: " + optifineJar);

            try (JarFile jar = new JarFile(optifineJar)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF")) {
                        String className = entry.getName().replace("/", ".").replace(".class", "");

                        // 特别加载关键类
                        if (isCriticalClass(className)) {
                            System.out.println("[OptiFabric] 应急加载类: " + className);
                            loadClassFromJar(jar, entry, className);
                        }
                    }
                }
            }

            System.out.println("[OptiFabric] 应急类加载完成");
        } catch (Exception e) {
            System.err.println("[OptiFabric] 应急类加载失败: " + e.getMessage());
        }
    }

    private static boolean isCriticalClass(String className) {
        return className.contains("def") ||
                className.contains("Config") ||
                className.contains("GLX") ||
                className.contains("OpenGL") ||
                className.contains("dej");
    }

    private static void loadClassFromJar(JarFile jar, JarEntry entry, String className) {
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] bytes = in.readAllBytes();

            // 使用ClassTinkerers提前加载类
            ClassTinkerers.define(className.replace(".", "/"), bytes);
            System.out.println("[OptiFabric] 成功加载: " + className);
        } catch (IOException e) {
            System.err.println("[OptiFabric] 加载类失败 " + className + ": " + e.getMessage());
        }
    }
}
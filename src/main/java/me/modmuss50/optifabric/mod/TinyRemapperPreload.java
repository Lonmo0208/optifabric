package me.modmuss50.optifabric.mod;

import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.io.IOException;
import java.net.*;
import java.util.function.Function;
import java.util.stream.Stream;

public class TinyRemapperPreload {
    private static final ClassLoader cl = FabricLauncherBase.getLauncher().getTargetClassLoader();

    public static void load() {
        Stream<String> trStream, asmStream, asmTreeStream;
        try {
            trStream = getEntries("net/fabricmc/tinyremapper/", "net/fabricmc/tinyremapper/TinyRemapper.class");
            asmStream = getEntries("org/objectweb/asm/", "org/objectweb/asm/MethodVisitor.class");
            asmTreeStream = getEntries("org/objectweb/asm/tree/", "org/objectweb/asm/tree/MethodNode.class");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Stream.concat(Stream.of("javax.lang.model.SourceVersion"), Stream.of(trStream, asmStream, asmTreeStream).flatMap(Function.identity())).forEach(clazz -> {
            try {
                Class.forName(clazz, false, cl);
            } catch (ClassNotFoundException e) {
                System.out.println("failed to find " + clazz);
            }
        });
    }

    private static Stream<String> getEntries(String pkg, String locatorClass) throws IOException {
        URL url = cl.getResource(locatorClass);
        return ((JarURLConnection) url.openConnection()).getJarFile().stream()
                .filter(jarEntry -> jarEntry.getName().contains(pkg) && jarEntry.getName().endsWith(".class"))
                .map(jarEntry -> jarEntry.getName().replace("/", ".").replace(".class", ""));
    }
}

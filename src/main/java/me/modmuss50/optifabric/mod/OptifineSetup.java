package me.modmuss50.optifabric.mod;

import com.chocohead.mm.api.ClassTinkerers;
import me.modmuss50.optifabric.*;
import me.modmuss50.optifabric.patcher.*;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.*;
import net.fabricmc.tinyremapper.IMappingProvider;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;

public class OptifineSetup {
    private final Path workingDir = FabricLoader.getInstance().getGameDir().resolve(".optifine");

    /**
     * locates and prepares Optifine for remapping
     *
     * @return pair of remapped Optifine jar with classes intended to be replaced removed and a {@link me.modmuss50.optifabric.patcher.ClassCache} with the rest of the classes which need to be replaced by {@link ClassTinkerers#addReplacement}
     */
    public Pair<Path, ClassCache> getRuntime() throws IOException, NoSuchAlgorithmException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        Files.createDirectories(this.workingDir);
        Path optifineModJar = OptifineVersion.findOptifineJar();
        byte[] modHash = IOUtils.fileHash(optifineModJar);
        Path versionDir = this.workingDir.resolve(OptifineVersion.version);
        Files.createDirectories(versionDir);
        Path remappedJar = versionDir.resolve("optifine-mapped.jar");
        Path optifinePatches = versionDir.resolve("optifine.classes");
        ClassCache classCache = null;
        if (Files.exists(remappedJar) && Files.exists(optifinePatches)) {
            classCache = ClassCache.read(optifinePatches.toFile());
            // validate that the class cache found is for the same input jar
            if (!Arrays.equals(classCache.getHash(), modHash)) {
                System.out.println("class cache is from a different OptiFine jar, deleting and re-generating");
                classCache = null;
                Files.delete(optifinePatches);
            }
        }

        if (Files.exists(remappedJar) && classCache != null) {
            System.out.println("found existing patched OptiFine jar, using that");
            return new Pair<>(remappedJar, classCache);
        }

        if (OptifineVersion.jarType == OptifineVersion.JarType.OPTIFINE_INSTALLER) {
            Path optifineMod = versionDir.resolve("optifine-mod.jar");
            if (!Files.exists(optifineMod)) {
                OptifineInstaller.extract(optifineModJar, optifineMod, this.getMinecraftJar(false));
            }
            optifineModJar = optifineMod;
        }

        System.out.println("setting up OptiFine for the first time, this may take a few seconds");

        // a jar without srgs
        Path jarOfTheFree = versionDir.resolve("optifine-jar-of-the-free.jar");
        List<String> srgs = new ArrayList<>();

        System.out.println("removing srg named entries from jar");

        // find all the srg named classes and remove them
        try (ZipFile fs = new ZipFile(optifineModJar.toFile())) {
            fs.stream().map(ZipEntry::getName).filter(name -> {
                if (name.startsWith("srg/") || name.startsWith("net/minecraft/")) {
                    return true;
                }
                if (name.startsWith("com/mojang/blaze3d/platform/") && name.contains("$")) {
                    String[] split = name.replace(".class", "").split("\\$");
                    return split.length >= 2 && split[1].length() > 2;
                }
                return false;
            }).forEach(srgs::add);
        }

        Files.deleteIfExists(jarOfTheFree);
        Files.copy(optifineModJar, jarOfTheFree);
        try (FileSystem fs = FileSystems.newFileSystem(jarOfTheFree, null)) {
            for (String s : srgs) {
                Files.deleteIfExists(fs.getPath(s));
            }
            for (String s : Optifabric.getExcludedClasses()) {
                Files.deleteIfExists(fs.getPath(s));
            }

            if ("1.1".equals(OptifineVersion.minecraftVersion)) {
                Path gameOption = fs.getPath("xt.class");
                if (Files.exists(gameOption)) {
                    byte[] bytes = Files.readAllBytes(gameOption);
                    byte[] target = "Signature".getBytes(StandardCharsets.UTF_8);
                    byte[] replacement = "Notanattr".getBytes(StandardCharsets.UTF_8);
                    outer:
                    for (int i = 0; i < bytes.length - target.length; ++i) {
                        for (int j = 0; j < target.length - 1; ++j) {
                            if (bytes[i + j] != target[j]) {
                                continue outer;
                            }
                        }

                        for (byte b : replacement) {
                            bytes[i++] = b;
                        }
                        break;
                    }
                    Files.write(gameOption, bytes);
                }
            }
        }

        System.out.println("building lambda fix mappings");
        LambdaRebuilder rebuilder = new LambdaRebuilder(jarOfTheFree, this.getMinecraftJar(false));
        rebuilder.buildLambdaMap();

        System.out.println("remapping OptiFine with fixed lambda names");
        Path lambdaFixJar = versionDir.resolve("optifine-lambda-fix.jar");
        RemapUtils.mapJar(lambdaFixJar, jarOfTheFree, rebuilder, this.getLibs());

        this.remapOptifine(lambdaFixJar, remappedJar);

        classCache = PatchSplitter.generateClassCache(remappedJar, optifinePatches, modHash);

        // we are done, lets get rid of the stuff we no longer need
        Files.deleteIfExists(lambdaFixJar);
        Files.deleteIfExists(jarOfTheFree);
        boolean keepExtractedJar = Boolean.parseBoolean(System.getProperty("optifabric.keepExtractedJar", "false"));
        if (OptifineVersion.jarType == OptifineVersion.JarType.OPTIFINE_INSTALLER && !keepExtractedJar) {
            Files.deleteIfExists(optifineModJar);
        }

        // TODO: for extract, make a remapped jar containing both class sets instead of individual files
        if (Boolean.parseBoolean(System.getProperty("optifabric.extract", "false"))) {
            System.out.println("extracting OptiFine classes");
            Path optifineClasses = versionDir.resolve("optifine-classes");
            if (Files.exists(optifineClasses)) {
                IOUtils.deleteDirectory(optifineClasses);
            }
            try (ZipFile fs = new ZipFile(remappedJar.toFile())) {
                fs.stream().forEach(entry -> {
                    try {
                        Path p = optifineClasses.resolve(entry.getName());
                        if (entry.isDirectory()) {
                            Files.createDirectories(p);
                        } else {
                            Files.createDirectories(p.getParent());
                            Files.createFile(p);
                            Files.write(p, IOUtils.toByteArray(fs.getInputStream(entry)));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        return new Pair<>(remappedJar, classCache);
    }

    private void remapOptifine(Path input, Path remappedJar) throws IOException {
        MappingResolver mappingResolver = FabricLoader.getInstance().getMappingResolver();
        String namespace = mappingResolver.getCurrentRuntimeNamespace();
        System.out.println("remapping OptiFine to " + namespace);
        List<Path> mcLibs = this.getLibs();
        mcLibs.remove(this.getMinecraftJar(true));
        mcLibs.add(this.getMinecraftJar(false));
        Collection<String> namespaces = mappingResolver.getNamespaces();
        String target;
        if (namespaces.contains("official")) {
            target = "official";
        } else if (namespaces.contains("clientOfficial")) {
            target = "clientOfficial";
        } else {
            throw new IllegalStateException("mappings have no obfuscated namespace?");
        }
        RemapUtils.mapJar(remappedJar, input, this.createMappings(target, namespace, true), mcLibs);
    }

    IMappingProvider createMappings(String from, String to, boolean forOptiFine) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        try (InputStream mappings = FabricLoader.class.getClassLoader().getResourceAsStream("mappings/mappings.tiny")) {
            // you've got bigger problems if you don't have a mappings set
            assert mappings != null;
            MappingReader.read(new InputStreamReader(mappings), tree);
        }
        int fromId = tree.getNamespaceId(from);
        return (out) -> {
            for (MappingTree.ClassMapping classDef : tree.getClasses()) {
                String className = classDef.getName(from);

                out.acceptClass(className, classDef.getName(to));

                for (MappingTree.FieldMapping field : classDef.getFields()) {
                    out.acceptField(new IMappingProvider.Member(className, field.getName(from), field.getDesc(fromId)), field.getName(to));
                }

                for (MappingTree.MethodMapping method : classDef.getMethods()) {
                    if (method.getName(from) == null || method.getName(to) == null /* || method.getName(from).equals(method.getName(to)) */) {
                        continue;
                    }
                    // 1.13.2: cwv.a(II)Z now overrides ayl.a(II)Z, need to remove the mapping
                    if (forOptiFine && "cwv".equals(className) && "a".equals(method.getName(from)) && "(II)Z".equals(method.getDesc(fromId))) {
                        continue;
                    }
                    out.acceptMethod(new IMappingProvider.Member(className, method.getName(from), method.getDesc(fromId)), method.getName(to));
                }
            }

            if (!forOptiFine) return;

            // TODO: automatically detect and resolve mapping conflicts in optifine patched classes
        };
    }

    List<Path> getLibs() {
        return FabricLauncherBase.getLauncher().getClassPath().stream().filter(Files::exists).collect(Collectors.toList());
    }

    // gets the official minecraft jar
    // if launch it will return named jar in dev
    Path getMinecraftJar(boolean launch) throws IOException {
        String givenJar = System.getProperty("optifabric.mc-jar");
        if (givenJar != null) {
            Path givenJarFile = Paths.get(givenJar);
            if (Files.exists(givenJarFile)) {
                return givenJarFile;
            } else {
                System.err.println("supplied minecraft jar at " + givenJar + " doesn't exist, falling back");
            }
        }

        Path gameJar = (Path) ((List<?>) FabricLoader.getInstance().getObjectShare().get("fabric-loader:inputGameJars")).get(0);
        if (!FabricLoader.getInstance().isDevelopmentEnvironment() || launch) {
            return gameJar;
        }

        Path versionDir = this.workingDir.resolve(OptifineVersion.version);
        Path remappedGameJar = versionDir.resolve("remapped-mc.jar");
        if (Files.exists(remappedGameJar)) return remappedGameJar; // users are going to have to manually delete this if they update their mappings

        MappingResolver mappingResolver = FabricLoader.getInstance().getMappingResolver();
        String namespace = mappingResolver.getCurrentRuntimeNamespace();
        System.out.println("remapping Minecraft to " + namespace);
        Collection<String> namespaces = mappingResolver.getNamespaces();
        String target;
        if (namespaces.contains("official")) {
            target = "official";
        } else if (namespaces.contains("clientOfficial")) {
            target = "clientOfficial";
        } else {
            throw new IllegalStateException("mappings have no obfuscated namespace?");
        }
        RemapUtils.mapJar(remappedGameJar, gameJar, this.createMappings(namespace, target, false), Collections.emptyList());
        return remappedGameJar;
    }
}

package me.modmuss50.optifabric.mod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.chocohead.mm.api.ClassTinkerers;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.spongepowered.asm.mixin.Mixins;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.mapping.tree.ClassDef;
import net.fabricmc.mapping.tree.FieldDef;
import net.fabricmc.mapping.tree.MethodDef;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.IMappingProvider.Member;
import net.fabricmc.tinyremapper.TinyRemapper.InputTag;

import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.patcher.LambdaRebuilder;
import me.modmuss50.optifabric.util.ASMUtils;
import me.modmuss50.optifabric.util.RemappingUtils;
import me.modmuss50.optifabric.util.ZipUtils;
import me.modmuss50.optifabric.util.ZipUtils.ZipTransformer;
import me.modmuss50.optifabric.util.ZipUtils.ZipVisitor;

public class OptifabricSetup implements Runnable {
	public static File optifineRuntimeJar = null;
	public static boolean usingScreenAPI;

	@Override
	public void run() {
		OptifineInjector injector;
		try {
			Pair<File, ClassCache> runtime = OptifabricSetup.getRuntime();
			optifineRuntimeJar = runtime.getLeft();
			ClassTinkerers.addURL(runtime.getLeft().toURI().toURL());

			injector = new OptifineInjector(runtime.getRight());
			injector.setup();
		} catch (Throwable e) {
			if (!OptifabricError.hasError()) {
				OptifineVersion.jarType = OptifineVersion.JarType.INTERNAL_ERROR;
				OptifabricError.setError(e, "Failed to load OptiFine, please report this!\n\n" + e.getMessage());
			}
			System.err.println("Failed to setup optifine:");
			e.printStackTrace();
			return;
		}

		// 强制加载所有Mixin配置
		Mixins.addConfiguration("optifabric.compat.fabric-renderer-api.mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-renderer-api.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-rendering.mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-rendering.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-rendering.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-rendering-data.mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-rendering-data.bonus-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-rendering-data.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.indigo.mixins.json");
		Mixins.addConfiguration("optifabric.compat.indigo.old-mixins.json");
		Mixins.addConfiguration("optifabric.compat.indigo.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.indigo.newer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.indigo.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-item-api.mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-screen-api.mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-screen-api.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-screen-api.newer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-screen-api.newerer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-screen-api.new3er-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-screen-api.new4er-mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-lifecycle-events.mixins.json");
		Mixins.addConfiguration("optifabric.compat.fabric-lifecycle-events.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.cloth.mixins.json");
		Mixins.addConfiguration("optifabric.compat.cloth.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.cloth.newer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.clothesline.mixins.json");
		Mixins.addConfiguration("optifabric.compat.trumpet-skeleton.mixins.json");
		Mixins.addConfiguration("optifabric.compat.multiconnect.mixins.json");
		Mixins.addConfiguration("optifabric.compat.now-playing.mixins.json");
		Mixins.addConfiguration("optifabric.compat.origins.mixins.json");
		Mixins.addConfiguration("optifabric.compat.origins.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.apoli.mixins.json");
		Mixins.addConfiguration("optifabric.compat.apoli-new.mixins.json");
		Mixins.addConfiguration("optifabric.compat.apoli-newer.mixins.json");
		Mixins.addConfiguration("optifabric.compat.apoli-newerer.mixins.json");
		Mixins.addConfiguration("optifabric.compat.apoli-newerer.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.additional-entity-attributes.mixins.json");
		Mixins.addConfiguration("optifabric.compat.staffofbuilding.mixins.json");
		Mixins.addConfiguration("optifabric.compat.sandwichable.mixins.json");
		Mixins.addConfiguration("optifabric.compat.sandwichable-old.mixins.json");
		Mixins.addConfiguration("optifabric.compat.sandwichable.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.astromine.mixins.json");
		Mixins.addConfiguration("optifabric.compat.carpet.mixins.json");
		Mixins.addConfiguration("optifabric.compat.carpet.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.carpet.extra-new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.hctm.mixins.json");
		Mixins.addConfiguration("optifabric.compat.mubble.mixins.json");
		Mixins.addConfiguration("optifabric.compat.dawn.older-mixins.json");
		Mixins.addConfiguration("optifabric.compat.dawn.old-mixins.json");
		Mixins.addConfiguration("optifabric.compat.dawn.mixins.json");
		Mixins.addConfiguration("optifabric.compat.phormat.mixins.json");
		Mixins.addConfiguration("optifabric.compat.chat-heads.mixins.json");
		Mixins.addConfiguration("optifabric.compat.age-of-exile.mixins.json");
		Mixins.addConfiguration("optifabric.compat.charm-older.mixins.json");
		Mixins.addConfiguration("optifabric.compat.charm-old.mixins.json");
		Mixins.addConfiguration("optifabric.compat.charm.mixins.json");
		Mixins.addConfiguration("optifabric.compat.charm-new.mixins.json");
		Mixins.addConfiguration("optifabric.compat.charm-plus.mixins.json");
		Mixins.addConfiguration("optifabric.compat.voxelmap.mixins.json");
		Mixins.addConfiguration("optifabric.compat.ae2.mixins.json");
		Mixins.addConfiguration("optifabric.compat.images-older.mixins.json");
		Mixins.addConfiguration("optifabric.compat.images-old.mixins.json");
		Mixins.addConfiguration("optifabric.compat.images.mixins.json");
		Mixins.addConfiguration("optifabric.compat.architectury-AB.mixins.json");
		Mixins.addConfiguration("optifabric.compat.architectury-AB.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.architectury-AB.newer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.architectury-AB.newerer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.architectury-AB.newererer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.architectury-AB.new4er-mixins.json");
		Mixins.addConfiguration("optifabric.compat.frex.mixins.json");
		Mixins.addConfiguration("optifabric.compat.frex-old.mixins.json");
		Mixins.addConfiguration("optifabric.compat.full-slabs.mixins.json");
		Mixins.addConfiguration("optifabric.compat.amecsapi.mixins.json");
		Mixins.addConfiguration("optifabric.compat.pswg.mixins.json");
		Mixins.addConfiguration("optifabric.compat.pswg.extra-mixins.json");
		Mixins.addConfiguration("optifabric.compat.custom-fog.mixins.json");
		Mixins.addConfiguration("optifabric.compat.smooth-chunks.mixins.json");
		Mixins.addConfiguration("optifabric.compat.enhancedcelestials.mixins.json");
		Mixins.addConfiguration("optifabric.compat.enhancedcelestials.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.cullparticles.mixins.json");
		Mixins.addConfiguration("optifabric.compat.aether.mixins.json");
		Mixins.addConfiguration("optifabric.compat.stacc.mixins.json");
		Mixins.addConfiguration("optifabric.compat.stacc.old-mixins.json");
		Mixins.addConfiguration("optifabric.compat.bannerpp.mixins.json");
		Mixins.addConfiguration("optifabric.compat.replaymod.mixins.json");
		Mixins.addConfiguration("optifabric.compat.replaymod.new-mixins.json");
		Mixins.addConfiguration("optifabric.compat.replaymod.newer-mixins.json");
		Mixins.addConfiguration("optifabric.compat.zoomify.mixins.json");
		Mixins.addConfiguration("optifabric.compat.borderlessmining.mixins.json");
		Mixins.addConfiguration("optifabric.compat.borderlessmining.new-mixins.json");

		// 核心OptiFine配置
		Mixins.addConfiguration("optifabric.optifine.mixins.json");
		Mixins.addConfiguration("optifabric.optifine.old-mixins.json");

		usingScreenAPI = true;
	}

	public static boolean isPresent(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	public static boolean isPresent(String modId, String versionRange) {
		return isPresent(modId, modMetadata -> compareVersions(versionRange, modMetadata));
	}

	private static boolean isPresent(String modId, Predicate<ModMetadata> extraChecks) {
		if (!isPresent(modId)) return false;

		Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(modId);
		ModMetadata modMetadata = modContainer.map(ModContainer::getMetadata).orElseThrow(() ->
				new RuntimeException("Failed to get mod container for " + modId)
		);
		return extraChecks.test(modMetadata);
	}

	private static boolean compareVersions(String versionRange, ModMetadata mod) {
		try {
			String currentVersion = mod.getVersion().getFriendlyString();
			String operator;
			String targetVersion;

			if (versionRange.startsWith(">=")) {
				operator = ">=";
				targetVersion = versionRange.substring(2);
			} else if (versionRange.startsWith(">")) {
				operator = ">";
				targetVersion = versionRange.substring(1);
			} else if (versionRange.startsWith("<=")) {
				operator = "<=";
				targetVersion = versionRange.substring(2);
			} else if (versionRange.startsWith("<")) {
				operator = "<";
				targetVersion = versionRange.substring(1);
			} else if (versionRange.startsWith("=")) {
				operator = "=";
				targetVersion = versionRange.substring(1);
			} else {
				operator = "=";
				targetVersion = versionRange;
			}

			int comparison = compareVersionNumbers(currentVersion, targetVersion);
			switch (operator) {
				case ">=": return comparison >= 0;
				case ">": return comparison > 0;
				case "<=": return comparison <= 0;
				case "<": return comparison < 0;
				case "=": return comparison == 0;
				default: return true;
			}
		} catch (Exception e) {
			return true;
		}
	}

	private static int compareVersionNumbers(String version1, String version2) {
		String[] parts1 = version1.split("[.-]");
		String[] parts2 = version2.split("[.-]");
		int maxLength = Math.max(parts1.length, parts2.length);

		for (int i = 0; i < maxLength; i++) {
			int part1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
			int part2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
			if (part1 != part2) {
				return Integer.compare(part1, part2);
			}
		}
		return 0;
	}

	private static int parseVersionPart(String part) {
		StringBuilder number = new StringBuilder();
		for (char c : part.toCharArray()) {
			if (Character.isDigit(c)) {
				number.append(c);
			} else {
				break;
			}
		}
		return number.length() > 0 ? Integer.parseInt(number.toString()) : 0;
	}

	@SuppressWarnings("deprecation")
	public static Pair<File, ClassCache> getRuntime() throws IOException {
		File workingDir = new File(FabricLoader.getInstance().getGameDirectory(), ".optifine");
		if (!workingDir.exists()) {
			FileUtils.forceMkdir(workingDir);
		}

		File optifineModJar = OptifineVersion.findOptifineJar();
		byte[] modHash;
		try (InputStream in = new FileInputStream(optifineModJar)) {
			modHash = DigestUtils.md5(in);
		}

		File versionDir = new File(workingDir, OptifineVersion.version);
		if (!versionDir.exists()) {
			FileUtils.forceMkdir(versionDir);
		}

		File remappedJar = new File(versionDir, "Optifine-mapped.jar");
		File optifinePatches = new File(versionDir, "Optifine.classes.gz");

		if (remappedJar.exists() && optifinePatches.exists()) {
			ClassCache classCache = ClassCache.read(optifinePatches);
			if (Arrays.equals(classCache.getHash(), modHash)) {
				System.out.println("Found existing patched optifine jar, using that");
				if (classCache.isConverted()) {
					classCache.save(optifinePatches);
				}
				return Pair.of(remappedJar, classCache);
			} else {
				System.out.println("Class cache mismatch, re-generating");
			}
		} else {
			System.out.println("Setting up optifine (first run), this may take seconds");
		}

		Path minecraftJar = getMinecraftJar();
		File workDir = Files.createTempDirectory("optifabric").toFile();

		if (OptifineVersion.jarType == OptifineVersion.JarType.OPTIFINE_INSTALLER) {
			File optifineMod = new File(workDir, "Optifine-mod.jar");
			out: for (int attempt = 1; attempt <= 3; attempt++) {
				runInstaller(optifineModJar, optifineMod, minecraftJar.toFile());
				if (ZipUtils.isValid(optifineMod)) break out;
				optifineMod.delete();
			}
			if (!ZipUtils.isValid(optifineMod)) {
				OptifineVersion.jarType = OptifineVersion.JarType.CORRUPT_ZIP;
				OptifabricError.setError("OptiFine installer failed (3 attempts), jar corrupt");
				throw new ZipException("OptiFine installer produced invalid jar");
			}
			optifineModJar = optifineMod;
		}

		File jarOfTheFree = new File(workDir, "Optifine-jarofthefree.jar");
		try (LambdaRebuilder rebuilder = new LambdaRebuilder(minecraftJar.toFile())) {
			ZipUtils.transform(optifineModJar, new ZipTransformer() {
				private final boolean correctRecords = FabricLoader.getInstance().isDevelopmentEnvironment();

				@Override
				public String mapName(ZipEntry entry) {
					return entry.getName().startsWith("notch/") ? entry.getName().substring(6) : entry.getName();
				}

				@Override
				public InputStream apply(ZipFile zip, ZipEntry entry) throws IOException {
					String name = entry.getName();
					if (name.startsWith("srg/")) return null;

					if (name.endsWith(".class") && !name.startsWith("net/") && !name.startsWith("optifine/") && !name.startsWith("javax/")) {
						ClassNode node = ASMUtils.readClass(zip, entry);
						rebuilder.findLambdas(node);

						if (correctRecords && (node.access & Opcodes.ACC_RECORD) != 0) {
							Map<String, Set<String>> descToNames = node.fields.stream()
									.filter(f -> !Modifier.isStatic(f.access))
									.collect(Collectors.groupingBy(
											f -> f.desc,
											Collectors.mapping(
													f -> FabricLoader.getInstance().getMappingResolver().mapFieldName("official", node.name, f.name, f.desc),
													Collectors.toSet()
											)
									));
							node.recordComponents.forEach(comp -> {
								Set<String> existing = descToNames.get(comp.descriptor);
								if (existing != null && existing.contains(comp.name)) {
									String desc = "()" + comp.descriptor;
									node.methods.removeIf(m -> m.name.equals(comp.name) && m.desc.equals(desc));
								}
							});
						}

						ClassWriter writer = new ClassWriter(0);
						node.accept(writer);
						return new ByteArrayInputStream(writer.toByteArray());
					}
					return zip.getInputStream(entry);
				}
			}, jarOfTheFree);
		}

		String namespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();
		File completeJar = new File(workDir, "Optifine-remapped.jar");
		remapOptifine(
				jarOfTheFree.toPath(),
				getLibs(minecraftJar),
				completeJar.toPath(),
				createMappings("official", namespace, new LambdaRebuilder(minecraftJar.toFile()))
		);

		for (UnaryOperator<File> transformer : FabricLoader.getInstance().getEntrypoints("optifabric:transformer", UnaryOperator.class)) {
			File transformed = transformer.apply(completeJar);
			if (transformed == null || !transformed.canRead()) {
				throw new IllegalStateException("Transformer returned invalid jar: " + transformed);
			}
			completeJar = transformed;
		}

		Consumer<ZipVisitor> jarFinaliser;
		if (remappedJar.exists() && !remappedJar.delete()) {
			System.err.println("Warning: Could not delete old jar, using temp jar");
			remappedJar = completeJar;
			jarFinaliser = v -> ZipUtils.filterInPlace(completeJar, v);
		} else {
			jarFinaliser = v -> ZipUtils.filter(completeJar, v, remappedJar);
		}

		if (optifinePatches.exists() && !optifinePatches.delete()) {
			System.err.println("Warning: Could not delete old patches, using temp patches");
			optifinePatches = new File(workDir, "Optifine.classes.gz");
		}

		workDir.deleteOnExit();
		for (File file : workDir.listFiles()) file.deleteOnExit();

		boolean extract = Boolean.getBoolean("optifabric.extract");
		if (extract) {
			System.out.println("Extracting optifine classes");
			File optifineClasses = new File(versionDir, "optifine-classes");
			if (optifineClasses.exists()) {
				FileUtils.deleteDirectory(optifineClasses);
			}
			ZipUtils.extract(completeJar, optifineClasses);
		}

		return Pair.of(remappedJar, generateClassCache(jarFinaliser, optifinePatches, modHash, extract));
	}

	private static void runInstaller(File installer, File output, File minecraftJar) throws IOException {
		System.out.println("Running optifine patcher");

		try (URLClassLoader classLoader = new URLClassLoader(new URL[]{installer.toURI().toURL()}, OptifabricSetup.class.getClassLoader())) {
			Class<?> clazz = classLoader.loadClass("optifine.Patcher");
			Method method = clazz.getDeclaredMethod("process", File.class, File.class, File.class);
			method.invoke(null, minecraftJar, installer, output);
		} catch (ReflectiveOperationException | MalformedURLException e) {
			throw new RuntimeException("Error running OptiFine patcher at " + installer + " on " + minecraftJar, e);
		}
	}

	private static void remapOptifine(Path input, Path[] libraries, Path output, IMappingProvider mappings) throws IOException {
		try (TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(mappings)
				.build()) {

			remapper.read(input, false, (InputTag[]) null);

			for (Path lib : libraries) {
				remapper.read(lib, false, (InputTag[]) null);
			}

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
				outputConsumer.addNonClassFiles(input);
				remapper.apply(outputConsumer);
			}
		}
	}

	private static IMappingProvider createMappings(String from, String to, LambdaRebuilder rebuilder) {
		TinyTree tree = FabricLoader.getInstance().getMappingManager().getTinyTree();

		return out -> {
			tree.accept(new IMappingProvider() {
				@Override
				public void load(MappingAcceptor acceptor) {
					for (ClassDef classDef : tree.getClasses()) {
						for (FieldDef fieldDef : classDef.getFields()) {
							Member fieldMember = new Member(
									classDef.getName(from),
									fieldDef.getName(from),
									fieldDef.getDescriptor(from)
							);
							acceptor.acceptField(fieldMember, fieldDef.getName(to));
						}

						for (MethodDef methodDef : classDef.getMethods()) {
							Member methodMember = new Member(
									classDef.getName(from),
									methodDef.getName(from),
									methodDef.getDescriptor(from)
							);
							acceptor.acceptMethod(methodMember, methodDef.getName(to));
						}
					}
				}
			});
		};
	}

	private static Path getMinecraftJar() {
		Optional<ModContainer> minecraftContainer = FabricLoader.getInstance().getModContainer("minecraft");
		if (minecraftContainer.isPresent()) {
			return minecraftContainer.get().getModFile().getFilePath();
		}
		throw new IllegalStateException("Minecraft mod container not found");
	}

	private static Path[] getLibs(Path minecraftJar) {
		Set<Path> libs = new java.util.HashSet<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			mod.getModFile().getNestedJars().forEach(nested -> libs.add(nested.getFilePath()));
			libs.add(mod.getModFile().getFilePath());
		}
		libs.remove(minecraftJar);
		return libs.toArray(new Path[0]);
	}

	private static ClassCache generateClassCache(Consumer<ZipVisitor> jarFinaliser, File patchesFile, byte[] hash, boolean extract) throws IOException {
		ClassCache cache = new ClassCache(hash);
		jarFinaliser.accept((zip, entry) -> {
			String name = entry.getName();
			if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
				try (InputStream in = zip.getInputStream(entry)) {
					cache.putClass(name, IOUtils.toByteArray(in));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			return true;
		});

		cache.save(patchesFile);
		return cache;
	}
}
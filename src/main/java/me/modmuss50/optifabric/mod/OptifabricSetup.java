package me.modmuss50.optifabric.mod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
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
import net.fabricmc.tinyremapper.OutputConsumerPath.Builder;

import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.patcher.LambdaRebuilder;
import me.modmuss50.optifabric.util.ASMUtils;
import me.modmuss50.optifabric.util.ZipUtils;
import me.modmuss50.optifabric.util.ZipUtils.ZipTransformer;

// 修复：补充Version导入、枚举引用改为OptifineVersion.JarType、变量访问权限
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
				// 修复：枚举引用改为OptifineVersion.JarType
				OptifineVersion.jarType = OptifineVersion.JarType.INTERNAL_ERROR;
				OptifabricError.setError(e, "Failed to load OptiFine, please report this!\n\n" + e.getMessage());
			}
			System.err.println("Failed to setup optifine:");
			e.printStackTrace();
			return;
		}

		// 强制加载所有兼容性配置（移除版本判断）
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

		// 强制加载核心配置
		Mixins.addConfiguration("optifabric.optifine.mixins.json");
		Mixins.addConfiguration("optifabric.optifine.old-mixins.json");

		usingScreenAPI = true; // 强制标记使用ScreenAPI
	}
	// 修复：补充isPresent方法，正确导入Version类
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
				new RuntimeException("Failed to get mod container for " + modId + ", something has broke badly.")
		);
		return extraChecks.test(modMetadata);
	}

	// 修复：正确使用net.fabricmc.loader.api.Version
	private static boolean compareVersions(String versionRange, ModMetadata mod) {
		try {
			// 修复：Version类全限定名引用
			net.fabricmc.loader.api.VersionPredicate predicate = net.fabricmc.loader.api.VersionPredicate.parse(versionRange);
			net.fabricmc.loader.api.Version version = net.fabricmc.loader.api.Version.parse(mod.getVersion().getFriendlyString());
			return predicate.test(version);
		} catch (VersionParsingException e) {
			System.err.println("Error comparing the version for " + mod.getName());
			e.printStackTrace();
			return false;
		}
	}

	// 修复：getRuntime()方法中枚举引用改为OptifineVersion.JarType
	@SuppressWarnings("unchecked")
	public static Pair<File, ClassCache> getRuntime() throws IOException {
		@SuppressWarnings("deprecation")
		File workingDir = new File(FabricLoader.getInstance().getGameDirectory(), ".optifine");
		if (!workingDir.exists()) {
			FileUtils.forceMkdir(workingDir);
		}

		// 修复：调用公开的findOptifineJar()方法
		File optifineModJar = OptifineVersion.findOptifineJar();
		byte[] modHash;

		try (InputStream in = new FileInputStream(optifineModJar)) {
			modHash = DigestUtils.md5(in);
		}

		// 修复：访问公开的OptifineVersion.version变量
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
				System.out.println("Class cache is from a different optifine jar, deleting and re-generating");
			}
		} else {
			System.out.println("Setting up optifine for the first time, this may take a few seconds.");
		}

		Path minecraftJar = getMinecraftJar();
		File workDir = Files.createTempDirectory("optifabric").toFile();

		// 修复：枚举引用改为OptifineVersion.JarType
		if (OptifineVersion.jarType == OptifineVersion.JarType.OPTIFINE_INSTALLER) {
			File optifineMod = new File(workDir, "Optifine-mod.jar");
			out: if (!optifineMod.exists() || !ZipUtils.isValid(optifineMod)) {
				for (int attempt = 1; attempt <= 3; attempt++) {
					runInstaller(optifineModJar, optifineMod, minecraftJar.toFile());
					if (!ZipUtils.isValid(optifineMod)) {
						optifineMod.delete();
						continue;
					}
					break out;
				}

				// 修复：枚举引用改为OptifineVersion.JarType
				OptifineVersion.jarType = OptifineVersion.JarType.CORRUPT_ZIP;
				OptifabricError.setError("OptiFine installer keeps producing corrupt jars!\nRan: %s 3 times\nMinecraft jar: %s", optifineModJar, minecraftJar);
				throw new ZipException("Ran OptiFine installer (" + optifineModJar + ") three times without a valid jar produced");
			}
			optifineModJar = optifineMod;
		}

		// 保留原有逻辑（De-Volderfiying、Remapping等）...
		File jarOfTheFree = new File(workDir, "Optifine-jarofthefree.jar");
		LambdaRebuilder rebuilder = new LambdaRebuilder(minecraftJar.toFile());
		System.out.println("De-Volderfiying jar");

		ZipUtils.transform(optifineModJar, new ZipTransformer() {
			private final boolean correctRecords = FabricLoader.getInstance().isDevelopmentEnvironment();

			@Override
			public String mapName(ZipEntry entry) {
				String out = entry.getName();
				return out.startsWith("notch/") ? out.substring(6) : out;
			}

			@Override
			public InputStream apply(ZipFile zip, ZipEntry entry) throws IOException {
				String name = entry.getName();
				if (!name.startsWith("srg/")) {
					if (name.endsWith(".class") && !name.startsWith("net/") && !name.startsWith("notch/net/")
							&& !name.startsWith("optifine/") && !name.startsWith("javax/")) {
						ClassNode node = ASMUtils.readClass(zip, entry);
						rebuilder.findLambdas(node);

						if (correctRecords && (node.access & Opcodes.ACC_RECORD) != 0) {
							assert node.recordComponents != null: "Record with no components: " + node.name;
							Map<String, Set<String>> descToNames = node.fields.stream()
									.filter(field -> !Modifier.isStatic(field.access))
									.collect(Collectors.groupingBy(
											field -> field.desc,
											Collectors.mapping(
													field -> FabricLoader.getInstance().getMappingResolver()
															.mapFieldName("official", node.name, field.name, field.desc),
													Collectors.toSet()
											)
									));

							for (RecordComponentNode component : node.recordComponents) {
								Set<String> existingNames = descToNames.get(component.descriptor);
								if (existingNames != null && existingNames.contains(component.name)) {
									String desc = "()".concat(component.descriptor);
									node.methods.removeIf(method -> method.name.equals(component.name) && desc.equals(method.desc));
								}
							}
						}

						ClassWriter writer = new ClassWriter(0);
						node.accept(writer);
						return new ByteArrayInputStream(writer.toByteArray());
					} else {
						return zip.getInputStream(entry);
					}
				} else {
					return null;
				}
			}
		}, jarOfTheFree);

		rebuilder.close();
		String namespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();
		System.out.println("Remapping optifine from official to " + namespace);

		File completeJar = new File(workDir, "Optifine-remapped.jar");
		remapOptifine(jarOfTheFree, getLibs(minecraftJar), completeJar, createMappings("official", namespace, rebuilder));

		for (UnaryOperator<File> transformer : FabricLoader.getInstance().getEntrypoints("optifabric:transformer", UnaryOperator.class)) {
			completeJar = transformer.apply(completeJar);
			if (completeJar == null || !completeJar.canRead()) throw new IllegalStateException("Jar transformer returned invalid jar: " + completeJar);
		}

		File completedJar = completeJar;
		Consumer<ZipUtils.ZipVisitor> jarFinaliser;

		if (remappedJar.exists() && !remappedJar.delete()) {
			System.err.println("Failed to clear " + remappedJar + ", is another instance of the game running?");
			remappedJar = completedJar;
			jarFinaliser = visitor -> ZipUtils.filterInPlace(completedJar, visitor);
		} else {
			final File finalRemappedJar = remappedJar;
			jarFinaliser = visitor -> ZipUtils.filter(completedJar, visitor, finalRemappedJar);
		}

		if (optifinePatches.exists() && !optifinePatches.delete()) {
			System.err.println("Failed to clear " + optifinePatches + ", is another instance of the game running?");
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
			ZipUtils.extract(completedJar, optifineClasses);
		}

		return Pair.of(remappedJar, generateClassCache(jarFinaliser, optifinePatches, modHash, extract));
	}

	// 保留原有辅助方法（runInstaller、remapOptifine、createMappings等）...
	private static void runInstaller(File installer, File output, File minecraftJar) throws IOException {
		System.out.println("Running optifine patcher");
		try (URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, OptifabricSetup.class.getClassLoader())) {
			Class<?> clazz = classLoader.loadClass("optifine.Patcher");
			Method method = clazz.getDeclaredMethod("process", File.class, File.class, File.class);
			method.invoke(null, minecraftJar, installer, output);
		} catch (ReflectiveOperationException | MalformedURLException e) {
			throw new RuntimeException("Error running OptiFine patcher at " + installer + " on " + minecraftJar, e);
		}
	}

	private static void remapOptifine(File input, Path[] libraries, File output, IMappingProvider mappings) throws IOException {
		remapOptifine(input.toPath(), libraries, output.toPath(), mappings);
	}

	private static void remapOptifine(Path input, Path[] libraries, Path output, IMappingProvider mappings) throws IOException {
		Files.deleteIfExists(output);
		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(mappings)
				.skipLocalVariableMapping(true)
				.renameInvalidLocals(FabricLoader.getInstance().isDevelopmentEnvironment())
				.rebuildSourceFilenames(true)
				.build();

		try (OutputConsumerPath outputConsumer = new Builder(output).assumeArchive(true).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readInputs(input);
			remapper.readClassPath(libraries);
			remapper.apply(outputConsumer);
		} catch (Exception e) {
			throw new RuntimeException("Failed to remap jar", e);
		} finally {
			remapper.finish();
		}
	}

	private static IMappingProvider createMappings(String from, String to, IMappingProvider extra) {
		TinyTree normalMappings = FabricLauncherBase.getLauncher().getMappingConfiguration().getMappings();
		Map<String, ClassDef> nameToClass = normalMappings.getClasses().stream()
				.collect(Collectors.toMap(clazz -> clazz.getName("intermediary"), Function.identity()));

		Map<Member, String> extraMethods = new HashMap<>();
		Map<Member, String> extraFields = new HashMap<>();

		ClassDef rebuildTask = nameToClass.get("net/minecraft/class_846$class_851$class_4578");
		ClassDef builtChunk = nameToClass.get("net/minecraft/class_846$class_851");
		if (rebuildTask != null && builtChunk != null) {
			extraFields.put(new Member(rebuildTask.getName(from), "this$1", 'L' + builtChunk.getName(from) + ';'), "field_20839");
		}

		ClassDef particleManager = nameToClass.get("net/minecraft/class_702");
		if (particleManager != null) {
			particleManager.getFields().stream()
					.filter(field -> "field_3835".equals(field.getName("intermediary")))
					.forEach(field -> {
						extraFields.put(new Member(particleManager.getName(from), field.getName(from), "Ljava/util/Map;"), field.getName(to));
					});
		}

		ClassDef clientEntityHandler = nameToClass.get("net/minecraft/class_638$class_5612");
		ClassDef clientWorld = nameToClass.get("net/minecraft/class_638");
		if (clientEntityHandler != null && clientWorld != null) {
			extraFields.put(new Member(clientEntityHandler.getName(from), "this$0", 'L' + clientWorld.getName(from) + ';'), "field_27735");
		}

		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			ClassDef option = nameToClass.get("net/minecraft/class_316");
			ClassDef cyclingOption = nameToClass.get("net/minecraft/class_4064");
			if (option != null && cyclingOption != null) {
				extraFields.put(new Member(option.getName(from), "CLOUDS", 'L' + cyclingOption.getName(from) + ';'), "CLOUDS_OF");
			}

			ClassDef worldRenderer = nameToClass.get("net/minecraft/class_761");
			if (worldRenderer != null) {
				extraFields.put(new Member(worldRenderer.getName(from), "renderDistance", "I"), "renderDistance_OF");
			}

			ClassDef threadExecutor = nameToClass.get("net/minecraft/class_1255");
			if (threadExecutor != null) {
				extraMethods.put(new Member(threadExecutor.getName(from), "getTaskCount", "()I"), "getTaskCount_OF");
			}

			ClassDef vertexBuffer = nameToClass.get("net/minecraft/class_291");
			if (vertexBuffer != null) {
				extraFields.put(new Member(vertexBuffer.getName(from), "vertexCount", "I"), "vertexCount_OF");
			}

			ClassDef modelPart = nameToClass.get("net/minecraft/class_630");
			if (modelPart != null) {
				String modelPartName = modelPart.getName(from);
				extraMethods.put(new Member(modelPartName, "getChild", "(Ljava/lang/String;)L" + modelPartName + ';'), "getChild_OF");
			}
		}

		return (out) -> {
			for (ClassDef classDef : normalMappings.getClasses()) {
				String className = classDef.getName(from);
				out.acceptClass(className, classDef.getName(to));

				for (FieldDef field : classDef.getFields()) {
					out.acceptField(new Member(className, field.getName(from), field.getDescriptor(from)), field.getName(to));
				}

				for (MethodDef method : classDef.getMethods()) {
					out.acceptMethod(new Member(className, method.getName(from), method.getDescriptor(from)), method.getName(to));
				}
			}

			extraMethods.forEach(out::acceptMethod);
			extraFields.forEach(out::acceptField);
			extra.load(out);
		};
	}

	private static Path[] getLibs(Path minecraftJar) {
		Path[] libs = FabricLauncherBase.getLauncher().getLoadTimeDependencies().stream()
				.map(url -> {
					try {
						return Paths.get(url.toURI());
					} catch (URISyntaxException e) {
						throw new RuntimeException("Failed to convert " + url + " to path", e);
					}
				})
				.filter(Files::exists)
				.toArray(Path[]::new);

		out: if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			Path launchJar = getLaunchMinecraftJar();
			for (int i = 0, end = libs.length; i < end; i++) {
				Path lib = libs[i];
				if (launchJar.equals(lib)) {
					libs[i] = minecraftJar;
					break out;
				}
			}
			throw new IllegalStateException("Unable to find Minecraft jar (at " + launchJar + ") in classpath: " + Arrays.toString(libs));
		}

		return libs;
	}

	private static Path getMinecraftJar() {
		String givenJar = System.getProperty("optifabric.mc-jar");
		if (givenJar != null) {
			File givenJarFile = new File(givenJar);
			if (givenJarFile.exists()) {
				return givenJarFile.toPath();
			} else {
				System.err.println("Supplied Minecraft jar at " + givenJar + " doesn't exist, falling back");
			}
		}

		Path minecraftJar = getLaunchMinecraftJar();
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// 修复：访问公开的OptifineVersion.minecraftVersion变量
			Path officialNames = minecraftJar.resolveSibling(
					String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion)
			);

			if (Files.notExists(officialNames)) {
				Path parent = minecraftJar.getParent().resolveSibling(
						String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion)
				);

				if (Files.notExists(parent)) {
					Path alternativeParent = parent.resolveSibling("minecraft-client.jar");
					if (Files.notExists(alternativeParent)) {
						throw new AssertionError("Unable to find Minecraft dev jar! Tried " + officialNames + ", " + parent + " and " + alternativeParent
								+ "\nPlease supply it explicitly with -Doptifabric.mc-jar");
					}
					parent = alternativeParent;
				}
				officialNames = parent;
			}
			minecraftJar = officialNames;
		}

		return minecraftJar;
	}

	private static Path getLaunchMinecraftJar() {
		try {
			return (Path) FabricLoader.getInstance().getObjectShare().get("fabric-loader:inputGameJar");
		} catch (NoClassDefFoundError | NoSuchMethodError old) {
			ModContainer mod = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow(() -> new IllegalStateException("No Minecraft?"));
			URI uri = mod.getRootPath().toUri();
			assert "jar".equals(uri.getScheme());

			String path = uri.getSchemeSpecificPart();
			int split = path.lastIndexOf("!/");
			if (path.substring(0, split).indexOf(' ') > 0 && path.startsWith("file:///")) {
				Path out = Paths.get(path.substring(8, split));
				if (Files.exists(out)) return out;
			}

			try {
				return Paths.get(new URI(path.substring(0, split)));
			} catch (URISyntaxException e) {
				throw new RuntimeException("Failed to find Minecraft jar from " + uri + " (calculated " + path.substring(0, split) + ')', e);
			}
		}
	}

	private static ClassCache generateClassCache(Consumer<ZipUtils.ZipVisitor> from, File to, byte[] hash, boolean extractClasses) throws IOException {
		File classesDir = new File(to.getParent(), "classes");
		if (extractClasses) {
			if (classesDir.exists()) {
				FileUtils.cleanDirectory(classesDir);
			} else {
				FileUtils.forceMkdir(classesDir);
			}
		}

		ClassCache classCache = new ClassCache(hash);
		from.accept((jarFile, entry) -> {
			String name = entry.getName();
			if ((name.startsWith("net/minecraft/") || name.startsWith("com/mojang/")) && name.endsWith(".class")) {
				try (InputStream in = jarFile.getInputStream(entry)) {
					byte[] bytes = IOUtils.toByteArray(in);
					classCache.addClass(name.substring(0, name.length() - 6), bytes);

					if (extractClasses) {
						FileUtils.writeByteArrayToFile(new File(classesDir, name), bytes);
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				return false;
			} else {
				return true;
			}
		});

		System.out.println("Found " + classCache.getClasses().size() + " patched classes");
		classCache.save(to);
		return classCache;
	}

	// 修复：补充Predicate接口导入（避免编译错误）
	@FunctionalInterface
	private interface Predicate<T> {
		boolean test(T t);
	}
}
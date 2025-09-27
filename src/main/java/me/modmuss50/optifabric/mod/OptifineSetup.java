package me.modmuss50.optifabric.mod;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.RecordComponentNode;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.launch.common.FabricLauncherBase;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.OutputConsumerPath.Builder;

import me.modmuss50.optifabric.mod.OptifineVersion.JarType;
import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.patcher.LambdaRebuilder;
import me.modmuss50.optifabric.util.ASMUtils;
import me.modmuss50.optifabric.util.ZipUtils;
import me.modmuss50.optifabric.util.ZipUtils.ZipTransformer;
import me.modmuss50.optifabric.util.ZipUtils.ZipVisitor;

public class OptifineSetup {
	@SuppressWarnings("unchecked")
	public static Pair<File, ClassCache> getRuntime() throws IOException {
		@SuppressWarnings("deprecation") //Keeping backward compatibility with older Loader versions
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

			//Validate that the classCache found is for the same input jar
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

		if (OptifineVersion.jarType == JarType.OPTIFINE_INSTALLER) {
			File optifineMod = new File(workDir, "Optifine-mod.jar");

			out: if (!optifineMod.exists() || !ZipUtils.isValid(optifineMod)) {
				for (int attempt = 1; attempt <= 3; attempt++) {
					runInstaller(optifineModJar, optifineMod, minecraftJar.toFile());

					if (!ZipUtils.isValid(optifineMod)) {
						optifineMod.delete();
						continue;
					}

					break out; //Produced a valid extracted jar
				}

				OptifineVersion.jarType = JarType.CORRUPT_ZIP;
				OptifabricError.setError("OptiFine installer keeps producing corrupt jars!\nRan: %s 3 times\nMinecraft jar: %s", optifineModJar, minecraftJar);
				throw new ZipException("Ran OptiFine installer (" + optifineModJar + ") three times without a valid jar produced");
			}

			optifineModJar = optifineMod;
		}

		//A jar without srgs
		File jarOfTheFree = new File(workDir, "Optifine-jarofthefree.jar");
		LambdaRebuilder rebuilder = new LambdaRebuilder(minecraftJar.toFile());

		System.out.println("De-Volderfiying jar");

		//Find all the SRG named classes and remove them
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
					if (name.endsWith(".class") && !name.startsWith("net/") && !name.startsWith("notch/net/") && !name.startsWith("optifine/") && !name.startsWith("javax/")) {
						//System.out.println("Finding lambdas to fix in ".concat(name));
						ClassNode node = ASMUtils.readClass(zip, entry);

						rebuilder.findLambdas(node);
						if (correctRecords && (node.access & Opcodes.ACC_RECORD) != 0) {
							assert node.recordComponents != null: "Record with no components: " + node.name;
							Map<String, Set<String>> descToNames = node.fields.stream().filter(field -> !Modifier.isStatic(field.access)).collect(Collectors.groupingBy(field -> field.desc,
									Collectors.mapping(field -> FabricLoader.getInstance().getMappingResolver().mapFieldName("official", node.name, field.name, field.desc), Collectors.toSet())));

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

		Consumer<ZipVisitor> jarFinaliser;
		if (remappedJar.exists() && !remappedJar.delete()) {
			System.err.println("Failed to clear " + remappedJar + ", is another instance of the game running?");
			remappedJar = completedJar;
			jarFinaliser = visitor -> ZipUtils.filterInPlace(completedJar, visitor);
		} else {
			final File finalRemappedJar = remappedJar; //It's final in this code path... but javac knows it's not final everywhere
			jarFinaliser = visitor -> ZipUtils.filter(completedJar, visitor, finalRemappedJar);
		}
		if (optifinePatches.exists() && !optifinePatches.delete()) {
			System.err.println("Failed to clear " + optifinePatches + ", is another instance of the game running?");
			optifinePatches = new File(workDir, "Optifine.classes.gz");
		}

		//We are done, lets get rid of the stuff we no longer need
		workDir.deleteOnExit();
		for (File file : workDir.listFiles()) file.deleteOnExit();

		boolean extract = Boolean.getBoolean("optifabric.extract");
		if (extract) {
			System.out.println("Extracting optifine classes");
			File optifineClasses = new File(versionDir, "optifine-classes");
			if(optifineClasses.exists()){
				FileUtils.deleteDirectory(optifineClasses);
			}
			ZipUtils.extract(completedJar, optifineClasses);
		}

		return Pair.of(remappedJar, generateClassCache(jarFinaliser, optifinePatches, modHash, extract));
	}

	private static void runInstaller(File installer, File output, File minecraftJar) throws IOException {
		System.out.println("Running optifine patcher");

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, OptifineSetup.class.getClassLoader())) {
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

		Preloader.preloadTinyRemapper();
		TinyRemapper remapper = TinyRemapper.newRemapper().withMappings(mappings).skipLocalVariableMapping(true).renameInvalidLocals(FabricLoader.getInstance().isDevelopmentEnvironment()).rebuildSourceFilenames(true).build();

		try (OutputConsumerPath outputConsumer = new Builder(output).assumeArchive(true).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readInputsAsync(input);
			remapper.readClassPathAsync(libraries);

			remapper.apply(outputConsumer);
		} catch (Exception e) {
			throw new RuntimeException("Failed to remap jar", e);
		} finally {
			remapper.finish();
		}
	}

	//Optifine currently has two fields that match the same name as Yarn mappings, we'll rename Optifine's to something else
	private static IMappingProvider createMappings(String from, String to, IMappingProvider extra) {
		return new IMappingProvider() {
			private void standardExtraMappings(ContextualMappingProvider mapper) {
				String rebuildTask = "net/minecraft/class_846$class_851$class_4578";
				String builtChunk = "net/minecraft/class_846$class_851";
				mapper.add(ContextualMapping.forField((out, context) -> {
					out.acceptField(new Member(context.unmapClass(rebuildTask), "this$1", 'L' + context.unmapClass(builtChunk) + ';'), "field_20839");
				}).usingClasses(rebuildTask, builtChunk));

				String particleManager = "net/minecraft/class_702";
				ContextualMappingContext.Member factories = new ContextualMappingContext.Member(particleManager, "field_3835");
				mapper.add(ContextualMapping.forField((out, context) -> {
					ContextualMappingContext.Member fromFactories = context.unmapField(factories);
					out.acceptField(new Member(context.unmapClass(particleManager), fromFactories.name, "Ljava/util/Map;"), context.mapField(factories));
				}).usingClass(particleManager).usingField(factories));

				String clientEntityHandler = "net/minecraft/class_638$class_5612";
				String clientWorld = "net/minecraft/class_638";
				mapper.add(ContextualMapping.forField((out, context) -> {//Only present in 1.17.x (20w45a+)
					out.acceptField(new Member(context.unmapClass(clientEntityHandler), "this$0", 'L' + context.unmapClass(clientWorld) + ';'), "field_27735");
				}).usingClasses(clientEntityHandler, clientWorld));
			}

			private void devExtraMappings(ContextualMappingProvider mapper) {
				String option = "net/minecraft/class_316";
				String cyclingOption = "net/minecraft/class_4064";
				mapper.add(ContextualMapping.forField((out, context) -> {//Removed in 1.19
					out.acceptField(new Member(context.unmapClass(option), "CLOUDS", 'L' + context.unmapClass(cyclingOption) + ';'), "CLOUDS_OF");
				}).usingClasses(option, cyclingOption));

				String worldRenderer = "net/minecraft/class_761";
				mapper.add(ContextualMapping.forField((out, context) -> {
					out.acceptField(new Member(context.unmapClass(worldRenderer), "renderDistance", "I"), "renderDistance_OF");
				}).usingClass(worldRenderer));

				String threadExecutor = "net/minecraft/class_1255";
				mapper.add(ContextualMapping.forMethod((out, context) -> {
					out.acceptMethod(new Member(context.unmapClass(threadExecutor), "getTaskCount", "()I"), "getTaskCount_OF");
				}).usingClass(threadExecutor));

				String vertexBuffer = "net/minecraft/class_291";
				mapper.add(ContextualMapping.forField((out, context) -> {
					out.acceptField(new Member(context.unmapClass(vertexBuffer), "vertexCount", "I"), "vertexCount_OF");
				}).usingClass(vertexBuffer));

				String modelPart = "net/minecraft/class_630";
				mapper.add(ContextualMapping.forMethod((out, context) -> {
					String modelPartName = context.unmapClass(modelPart);
					out.acceptMethod(new Member(modelPartName, "getChild", "(Ljava/lang/String;)L" + modelPartName + ';'), "getChild_OF");
				}).usingClass(modelPart));
			}

			@Override
			public void load(MappingAcceptor parent) {
				ContextualMappingProvider out = new ContextualMappingProvider(parent);

				standardExtraMappings(out);
				if (FabricLoader.getInstance().isDevelopmentEnvironment()) devExtraMappings(out);
				out.setContextTransformer(mappings -> new IntermediaryContextTransformer(from, mappings));

				try (BufferedReader in = new BufferedReader(new InputStreamReader(OptifineSetup.class.getResourceAsStream("/mappings/mappings.tiny"), StandardCharsets.UTF_8))) {
					TinyUtils.createTinyMappingProvider(in, from, to).load(out);
				} catch (IOException e) {
					throw new RuntimeException("Failed to read " + from + " -> " + to + " mappings", e);
				}
				extra.load(out);
			}
		};
	}

	//Gets the minecraft librarys
	private static Path[] getLibs(Path minecraftJar) {
		Path[] libs = FabricLauncherBase.getLauncher().getLoadTimeDependencies().stream().map(url -> {
			try {
				return Paths.get(url.toURI());
			} catch (URISyntaxException e) {
				throw new RuntimeException("Failed to convert " + url + " to path", e);
			}
		}).filter(Files::exists).toArray(Path[]::new);

		out: if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			Path launchJar = getLaunchMinecraftJar();

			for (int i = 0, end = libs.length; i < end; i++) {
				Path lib = libs[i];

				if (launchJar.equals(lib)) {
					libs[i] = minecraftJar;
					break out;
				}
			}

			//Can't find the launch jar apparently, remapping will go wrong if it is left in
			throw new IllegalStateException("Unable to find Minecraft jar (at " + launchJar + ") in classpath: " + Arrays.toString(libs));
		}

		return libs;
	}

	//Gets the offical minecraft jar
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
			Path officialNames = minecraftJar.resolveSibling(String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion));

			if (Files.notExists(officialNames)) {
				Path parent = minecraftJar.getParent().resolveSibling(String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion));

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

			if (path.substring(0, split).indexOf(' ') > 0 && path.startsWith("file:///")) {//This is meant to be a URI...
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

	private static ClassCache generateClassCache(Consumer<ZipVisitor> from, File to, byte[] hash, boolean extractClasses) throws IOException {
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
				}

				return false; //Remove all the patched classes, we don't want these leaking directly on the classpath
			} else {
				return true;
			}
		});

		System.out.println("Found " + classCache.getClasses().size() + " patched classes");
		classCache.save(to);
		return classCache;
	}
}
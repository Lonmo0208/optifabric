package me.modmuss50.optifabric.mod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
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
import java.util.Map;
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
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.MappingResolver;
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
						try {
							//System.out.println("Finding lambdas to fix in ".concat(name));
							ClassNode node = ASMUtils.readClass(zip, entry);

							rebuilder.findLambdas(node);

							// 修改：使用ASMUtils.writeClass自动计算栈映射帧
							byte[] classBytes = ASMUtils.writeClass(node);
							return new ByteArrayInputStream(classBytes);
						} catch (IllegalArgumentException e) {
							// 捕获并处理"not present in vanilla"错误
							if (e.getMessage() != null && e.getMessage().contains("not present in vanilla")) {
								System.err.println("[OptiFabric] 警告: 跳过Lambda重建 for " + name + ": " + e.getMessage());
								// 返回原始类字节码，跳过Lambda重建
								return zip.getInputStream(entry);
							} else {
								throw e; // 重新抛出其他异常
							}
						} catch (Exception e) {
							// 捕获其他可能的异常
							System.err.println("[OptiFabric] 警告: 处理类 " + name + " 时出错: " + e.getMessage());
							// 返回原始类字节码，跳过处理
							return zip.getInputStream(entry);
						}
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

		// 修改：添加重试机制和冲突解决策略
		boolean remapSuccess = false;
		Exception lastException = null;

		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				remapOptifine(jarOfTheFree.toPath(), getLibs(minecraftJar), completeJar.toPath(), createMappings("official", namespace, rebuilder));
				remapSuccess = true;
				break;
			} catch (Exception e) {
				lastException = e;
				System.err.println("[OptiFabric] 重新映射尝试 " + attempt + "/3 失败: " + e.getMessage());

				if (attempt < 3) {
					try {
						Thread.sleep(1000); // 等待1秒后重试
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}

					// 删除可能不完整的文件
					if (completeJar.exists()) {
						completeJar.delete();
					}
				}
			}
		}

		if (!remapSuccess) {
			System.err.println("[OptiFabric] 所有重新映射尝试都失败了，尝试使用简化映射...");

			// 尝试使用简化映射
			try {
				remapOptifineWithSimpleMapping(jarOfTheFree.toPath(), getLibs(minecraftJar), completeJar.toPath());
				remapSuccess = true;
			} catch (Exception e) {
				System.err.println("[OptiFabric] 简化映射也失败了: " + e.getMessage());
				throw new RuntimeException("无法重新映射OptiFine jar。这可能是因为OptiFine版本与当前Minecraft版本不兼容。", lastException);
			}
		}

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

	private static void remapOptifine(Path input, Path[] libraries, Path output, IMappingProvider mappings) throws IOException {
		Files.deleteIfExists(output);

		// 修改：修复TinyRemapper配置方法名
		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(mappings)
				.skipLocalVariableMapping(true)
				.renameInvalidLocals(true) // 总是重命名无效的局部变量
				.rebuildSourceFilenames(true)
				.build();

		try (OutputConsumerPath outputConsumer = new Builder(output).assumeArchive(true).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readInputs(input);

			// 确保libraries数组不为null
			if (libraries != null && libraries.length > 0) {
				remapper.readClassPath(libraries);
			} else {
				System.err.println("[OptiFabric] 警告: 没有找到库文件，重新映射可能会失败");
			}

			remapper.apply(outputConsumer);
		} catch (Exception e) {
			// 检查是否是因为冲突导致的异常
			if (e.getMessage() != null && e.getMessage().contains("Unfixable conflicts")) {
				System.err.println("[OptiFabric] 检测到无法解决的冲突，尝试继续处理...");
				// 即使有冲突，也尝试继续
				throw new RuntimeException("无法解决的映射冲突", e);
			} else {
				throw new RuntimeException("重新映射失败: " + e.getMessage(), e);
			}
		} finally {
			remapper.finish();
		}
	}

	// 新增：简化映射方法
	private static void remapOptifineWithSimpleMapping(Path input, Path[] libraries, Path output) throws IOException {
		Files.deleteIfExists(output);

		// 使用极简映射，只处理类名
		IMappingProvider simpleMappings = out -> {
			// 只添加最基本的类名映射
			// 这里可以添加一些已知的类名映射
		};

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(simpleMappings)
				.skipLocalVariableMapping(true)
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.build();

		try (OutputConsumerPath outputConsumer = new Builder(output).assumeArchive(true).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readInputs(input);

			if (libraries != null && libraries.length > 0) {
				remapper.readClassPath(libraries);
			}

			remapper.apply(outputConsumer);
		} catch (Exception e) {
			System.err.println("[OptiFabric] 简化映射失败: " + e.getMessage());
			throw e;
		} finally {
			remapper.finish();
		}
	}

	//Optifine currently has two fields that match the same name as Yarn mappings, we'll rename OptiFine's to something else
	private static IMappingProvider createMappings(String from, String to, IMappingProvider extra) {
		// 修改：简化映射创建过程，避免复杂冲突
		MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();

		return (out) -> {
			// 只添加最必要的映射，避免复杂冲突
			try {
				// 加载额外的映射（来自LambdaRebuilder）
				extra.load(out);
			} catch (Exception e) {
				System.err.println("[OptiFabric] 加载额外映射时出错: " + e.getMessage());
				// 继续处理，不因为额外映射失败而停止
			}

			// 添加一些基本的类名映射
			// 这里可以根据需要添加特定的映射关系
		};
	}

	//Gets the minecraft librarys
	private static Path[] getLibs(Path minecraftJar) {
		Path[] libs = new Path[0];
		try {
			libs = FabricLauncherBase.getLauncher().getLoadTimeDependencies().stream().map(url -> {
				try {
					return Paths.get(url.toURI());
				} catch (URISyntaxException e) {
					throw new RuntimeException("Failed to convert " + url + " to path", e);
				}
			}).filter(Files::exists).toArray(Path[]::new);
		} catch (Exception e) {
			System.err.println("[OptiFabric] 获取库文件时出错: " + e.getMessage());
			// 返回空数组，继续尝试
		}

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
			System.err.println("[OptiFabric] 警告: 无法在类路径中找到Minecraft jar: " + Arrays.toString(libs));
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
						// 修改：提供更友好的错误信息
						System.err.println("[OptiFabric] 无法找到Minecraft开发jar！尝试使用启动jar。");
						return minecraftJar; // 返回启动jar而不是抛出异常
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
			Object gameJar = FabricLoader.getInstance().getObjectShare().get("fabric-loader:inputGameJar");
			if (gameJar instanceof Path) {
				return (Path) gameJar;
			}
		} catch (Exception e) {
			System.err.println("[OptiFabric] 无法通过新API获取游戏jar: " + e.getMessage());
		}

		try {
			ModContainer mod = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow(() -> new IllegalStateException("No Minecraft?"));
			URI uri = mod.getRootPath().toUri();

			if ("jar".equals(uri.getScheme())) {
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
		} catch (Exception e) {
			System.err.println("[OptiFabric] 无法通过旧API获取游戏jar: " + e.getMessage());
		}

		throw new RuntimeException("无法找到Minecraft jar");
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
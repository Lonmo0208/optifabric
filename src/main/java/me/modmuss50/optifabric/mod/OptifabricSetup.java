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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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
import org.objectweb.asm.Type;
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
import net.fabricmc.tinyremapper.OutputConsumerPath.Builder;

import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.patcher.LambdaRebuilder;
import me.modmuss50.optifabric.util.ASMUtils;
import me.modmuss50.optifabric.util.RemappingUtils; // 补充RemappingUtils导入（之前遗漏）
import me.modmuss50.optifabric.util.ZipUtils;
import me.modmuss50.optifabric.util.ZipUtils.ZipTransformer;

// 最终正确版：修复语法错误+逻辑冗余，确保强行注入不拦截
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

		// 【关键】仅保留“强制加载所有Mixin”（删除原有条件判断，满足强行注入需求）
		// 兼容性Mixin配置
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

		// 核心OptiFine配置（强制加载，不做版本拦截）
		Mixins.addConfiguration("optifabric.optifine.mixins.json");
		Mixins.addConfiguration("optifabric.optifine.old-mixins.json");

		usingScreenAPI = true; // 强制标记使用ScreenAPI，避免兼容性判断
	}

	// 修复：isPresent方法（修正变量引用错误，恢复正确逻辑）
	public static boolean isPresent(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	public static boolean isPresent(String modId, String versionRange) {
		return isPresent(modId, modMetadata -> compareVersions(versionRange, modMetadata));
	}

	// 修复：删除未定义的versionRange变量，正确调用extraChecks.test()
	private static boolean isPresent(String modId, Predicate<ModMetadata> extraChecks) {
		if (!isPresent(modId)) return false;

		Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(modId);
		ModMetadata modMetadata = modContainer.map(ModContainer::getMetadata).orElseThrow(() ->
				new RuntimeException("Failed to get mod container for " + modId + ", something has broke badly.")
		);
		return extraChecks.test(modMetadata); // 原逻辑：通过Predicate传递版本校验
	}

	// 基础版本范围比较（支持>=、>、<=、<、=，解析失败时默认返回true，不拦截）
	private static boolean compareVersions(String versionRange, ModMetadata mod) {
		try {
			String currentVersion = mod.getVersion().getFriendlyString();
			String operator;
			String targetVersion;

			// 提取操作符和目标版本
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

			// 比较版本号（按.分割数字）
			int comparison = compareVersionNumbers(currentVersion, targetVersion);
			switch (operator) {
				case ">=": return comparison >= 0;
				case ">": return comparison > 0;
				case "<=": return comparison <= 0;
				case "<": return comparison < 0;
				case "=": return comparison == 0;
				default: return true; // 未知操作符时不拦截
			}
		} catch (Exception e) {
			return true; // 版本解析失败时不拦截（强行注入核心需求）
		}
	}

	// 按数字分割比较版本（忽略非数字字符，如1.19.2-beta→1.19.2）
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

	// 提取版本片段中的数字（如"beta1"→1，"123a"→123）
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

	// 修复：getRuntime()方法（补充缺失的extract变量定义，确保逻辑连贯）
	@SuppressWarnings("unchecked")
	public static Pair<File, ClassCache> getRuntime() throws IOException {
		@SuppressWarnings("deprecation")
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

		// 复用缓存逻辑（不拦截，仅校验哈希）
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

		// 处理OptiFine安装包（不拦截版本，强制转换）
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

		// De-Volderfiying：清理OptiFine的SRG命名
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
					if (name.startsWith("srg/")) return null; // 删除SRG类

					if (name.endsWith(".class") && !name.startsWith("net/") && !name.startsWith("optifine/") && !name.startsWith("javax/")) {
						ClassNode node = ASMUtils.readClass(zip, entry);
						rebuilder.findLambdas(node);

						// 修复Record类的方法冲突（开发环境）
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

		// 强制重映射（忽略版本差异，按当前Minecraft的映射规则）
		String namespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();
		File completeJar = new File(workDir, "Optifine-remapped.jar");
		remapOptifine(jarOfTheFree.toPath(), getLibs(minecraftJar), completeJar, createMappings("official", namespace, new LambdaRebuilder(minecraftJar.toFile())));

		// 应用外部Transformer（如其他 mods 的OptiFabric扩展）
		for (UnaryOperator<File> transformer : FabricLoader.getInstance().getEntrypoints("optifabric:transformer", UnaryOperator.class)) {
			File transformed = transformer.apply(completeJar);
			if (transformed == null || !transformed.canRead()) {
				throw new IllegalStateException("Transformer returned invalid jar: " + transformed);
			}
			completeJar = transformed;
		}

		// 处理输出文件（覆盖旧文件，确保注入成功）
		Consumer<ZipUtils.ZipVisitor> jarFinaliser;
		if (remappedJar.exists() && !remappedJar.delete()) {
			System.err.println("Warning: Could not delete old jar, using temp jar");
			remappedJar = completeJar;
			jarFinaliser = v -> ZipUtils.filterInPlace(completeJar, v);
		} else {
			jarFinaliser = v -> ZipUtils.filter(completeJar, v, remappedJar);
		}

		// 清理旧补丁文件
		if (optifinePatches.exists() && !optifinePatches.delete()) {
			System.err.println("Warning: Could not delete old patches, using temp patches");
			optifinePatches = new File(workDir, "Optifine.classes.gz");
		}

		// 标记临时目录删除（退出时清理）
		workDir.deleteOnExit();
		for (File file : workDir.listFiles()) file.deleteOnExit();

		// 可选：提取类文件到本地（调试用）
		boolean extract = Boolean.getBoolean("optifabric.extract");
		if (extract) {
			File optifineClasses = new File(versionDir, "optifine-classes");
			if (optifineClasses.exists()) FileUtils.deleteDirectory(optifineClasses);
			ZipUtils.extract(completeJar, optifineClasses);
		}

		// 生成类缓存并返回
		ClassCache classCache = generateClassCache(jarFinaliser, optifinePatches, modHash, extract);
		return Pair.of(remappedJar, classCache);
	}

	// 执行OptiFine安装包的转换逻辑
	private static void runInstaller(File installer, File output, File minecraftJar) throws IOException {
		try (URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, OptifabricSetup.class.getClassLoader())) {
			Class<?> patcherClass = classLoader.loadClass("optifine.Patcher");
			Method processMethod = patcherClass.getDeclaredMethod("process", File.class, File.class, File.class);
			processMethod.invoke(null, minecraftJar, installer, output);
		} catch (ReflectiveOperationException | MalformedURLException e) {
			throw new RuntimeException("Failed to run OptiFine installer: " + e.getMessage(), e);
		}
	}

	// 重映射OptiFine的类（适配当前Minecraft版本）
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
			throw new RuntimeException("Failed to remap OptiFine jar: " + e.getMessage(), e);
		} finally {
			remapper.finish();
		}
	}

	// 创建重映射规则（适配Fabric的映射表）
	private static IMappingProvider createMappings(String from, String to, IMappingProvider extra) {
		TinyTree normalMappings = FabricLauncherBase.getLauncher().getMappingConfiguration().getMappings();
		Map<String, ClassDef> nameToClass = normalMappings.getClasses().stream()
				.collect(Collectors.toMap(c -> c.getName("intermediary"), Function.identity()));

		Map<Member, String> extraMethods = new HashMap<>();
		Map<Member, String> extraFields = new HashMap<>();

		// 修复OptiFine与Minecraft的字段名冲突
		ClassDef rebuildTask = nameToClass.get("net/minecraft/class_846$class_851$class_4578");
		ClassDef builtChunk = nameToClass.get("net/minecraft/class_846$class_851");
		if (rebuildTask != null && builtChunk != null) {
			extraFields.put(new Member(rebuildTask.getName(from), "this$1", 'L' + builtChunk.getName(from) + ';'), "field_20839");
		}

		ClassDef particleManager = nameToClass.get("net/minecraft/class_702");
		if (particleManager != null) {
			particleManager.getFields().stream()
					.filter(f -> "field_3835".equals(f.getName("intermediary")))
					.forEach(f -> extraFields.put(new Member(particleManager.getName(from), f.getName(from), "Ljava/util/Map;"), f.getName(to)));
		}

		ClassDef clientEntityHandler = nameToClass.get("net/minecraft/class_638$class_5612");
		ClassDef clientWorld = nameToClass.get("net/minecraft/class_638");
		if (clientEntityHandler != null && clientWorld != null) {
			extraFields.put(new Member(clientEntityHandler.getName(from), "this$0", 'L' + clientWorld.getName(from) + ';'), "field_27735");
		}

		// 开发环境额外修复
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
				String mpName = modelPart.getName(from);
				extraMethods.put(new Member(mpName, "getChild", "(Ljava/lang/String;)L" + mpName + ';'), "getChild_OF");
			}
		}

		// 合并所有映射规则
		return (out) -> {
			// 基础Minecraft映射
			for (ClassDef classDef : normalMappings.getClasses()) {
				String className = classDef.getName(from);
				out.acceptClass(className, classDef.getName(to));
				classDef.getFields().forEach(f -> out.acceptField(new Member(className, f.getName(from), f.getDescriptor(from)), f.getName(to)));
				classDef.getMethods().forEach(m -> out.acceptMethod(new Member(className, m.getName(from), m.getDescriptor(from)), m.getName(to)));
			}
			// 额外冲突修复
			extraMethods.forEach(out::acceptMethod);
			extraFields.forEach(out::acceptField);
			// Lambda修复映射
			extra.load(out);
		};
	}

	// 获取Minecraft依赖库（用于重映射）
	private static Path[] getLibs(Path minecraftJar) {
		Path[] libs = FabricLauncherBase.getLauncher().getLoadTimeDependencies().stream()
				.map(url -> {
					try {
						return Paths.get(url.toURI());
					} catch (URISyntaxException e) {
						throw new RuntimeException("Failed to resolve library: " + url, e);
					}
				})
				.filter(Files::exists)
				.toArray(Path[]::new);

		// 开发环境替换Minecraft Jar为官方命名的Jar
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			Path launchJar = getLaunchMinecraftJar();
			for (int i = 0; i < libs.length; i++) {
				if (launchJar.equals(libs[i])) {
					libs[i] = minecraftJar;
					return libs;
				}
			}
			throw new IllegalStateException("Minecraft jar not found in classpath: " + Arrays.toString(libs));
		}
		return libs;
	}

	// 获取当前运行的Minecraft Jar路径
	private static Path getMinecraftJar() {
		// 优先使用用户指定的Jar（-Doptifabric.mc-jar）
		String givenJar = System.getProperty("optifabric.mc-jar");
		if (givenJar != null) {
			File givenFile = new File(givenJar);
			if (givenFile.exists()) return givenFile.toPath();
			System.err.println("Supplied Minecraft jar not found, falling back");
		}

		Path minecraftJar = getLaunchMinecraftJar();
		// 开发环境适配官方Jar命名（如minecraft-1.19.2-client.jar）
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			Path officialJar = minecraftJar.resolveSibling(String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion));
			if (!Files.exists(officialJar)) {
				officialJar = minecraftJar.getParent().resolveSibling(String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion));
			}
			if (!Files.exists(officialJar)) {
				officialJar = officialJar.resolveSibling("minecraft-client.jar");
			}
			if (!Files.exists(officialJar)) {
				throw new AssertionError("Minecraft dev jar not found! Use -Doptifabric.mc-jar to specify");
			}
			minecraftJar = officialJar;
		}
		return minecraftJar;
	}

	// 从Fabric Loader获取Minecraft Jar路径
	private static Path getLaunchMinecraftJar() {
		try {
			return (Path) FabricLoader.getInstance().getObjectShare().get("fabric-loader:inputGameJar");
		} catch (NoClassDefFoundError | NoSuchMethodError oldLoader) {
			ModContainer mcContainer = FabricLoader.getInstance().getModContainer("minecraft")
					.orElseThrow(() -> new IllegalStateException("Minecraft mod container not found"));
			URI jarUri = mcContainer.getRootPath().toUri();
			if (!"jar".equals(jarUri.getScheme())) {
				throw new IllegalStateException("Minecraft is not a jar: " + jarUri);
			}
			String schemePart = jarUri.getSchemeSpecificPart();
			int split = schemePart.lastIndexOf("!/");
			try {
				return Paths.get(new URI(schemePart.substring(0, split)));
			} catch (URISyntaxException e) {
				throw new RuntimeException("Failed to parse Minecraft jar URI: " + schemePart, e);
			}
		}
	}

	// 生成类缓存（用于OptiFine注入）
	private static ClassCache generateClassCache(Consumer<ZipUtils.ZipVisitor> from, File to, byte[] hash, boolean extractClasses) throws IOException {
		File classesDir = new File(to.getParent(), "classes");
		if (extractClasses) {
			if (classesDir.exists()) FileUtils.cleanDirectory(classesDir);
			else FileUtils.forceMkdir(classesDir);
		}

		ClassCache classCache = new ClassCache(hash);
		from.accept((jarFile, entry) -> {
			String name = entry.getName();
			// 仅缓存Minecraft/Com Mojang的类（OptiFine修改过的）
			if ((name.startsWith("net/minecraft/") || name.startsWith("com/mojang/")) && name.endsWith(".class")) {
				try (InputStream in = jarFile.getInputStream(entry)) {
					byte[] bytes = IOUtils.toByteArray(in);
					classCache.addClass(name.substring(0, name.length() - 6), bytes);
					// 提取类文件到本地（调试用）
					if (extractClasses) {
						FileUtils.writeByteArrayToFile(new File(classesDir, name), bytes);
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				return false; // 从最终Jar中移除这些类（通过缓存注入）
			}
			return true;
		});

		System.out.println("Cached " + classCache.getClasses().size() + " patched classes");
		classCache.save(to);
		return classCache;
	}

	// 内部Predicate接口（避免依赖外部定义）
	@FunctionalInterface
	private interface Predicate<T> {
		boolean test(T t);
	}

	// 内部BooleanSupplier接口（适配FeatureFinder）
	@FunctionalInterface
	private interface BooleanSupplier {
		boolean getAsBoolean();
	}

	// 内部FeatureFinder抽象类（原代码依赖，避免外部引用错误）
	private abstract static class FeatureFinder implements BooleanSupplier {
		private boolean haveLooked, isPresent;

		protected abstract boolean isPresent();

		@Override
		public boolean getAsBoolean() {
			if (!haveLooked) {
				isPresent = isPresent();
				haveLooked = true;
			}
			return isPresent;
		}
	}
}
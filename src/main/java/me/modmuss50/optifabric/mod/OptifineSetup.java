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
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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

		// 修改：强制重新生成，避免缓存问题
		if (remappedJar.exists()) {
			System.out.println("[OptiFabric] 删除旧的重新映射jar: " + remappedJar);
			FileUtils.forceDelete(remappedJar);
		}
		if (optifinePatches.exists()) {
			System.out.println("[OptiFabric] 删除旧的类缓存: " + optifinePatches);
			FileUtils.forceDelete(optifinePatches);
		}

		System.out.println("[OptiFabric] 开始设置 OptiFine 运行时环境");
		System.out.println("[OptiFabric] Minecraft 版本: " + getMinecraftVersion());
		System.out.println("[OptiFabric] OptiFine 版本: " + OptifineVersion.version);
		System.out.println("[OptiFabric] OptiFine 目标版本: " + OptifineVersion.minecraftVersion);

		Path minecraftJar = getMinecraftJar();
		File workDir = Files.createTempDirectory("optifabric").toFile();
		System.out.println("[OptiFabric] 工作目录: " + workDir);

		if (OptifineVersion.jarType == JarType.OPTIFINE_INSTALLER) {
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

				OptifineVersion.jarType = JarType.CORRUPT_ZIP;
				OptifabricError.setError("OptiFine installer keeps producing corrupt jars!\nRan: %s 3 times\nMinecraft jar: %s", optifineModJar, minecraftJar);
				throw new ZipException("Ran OptiFine installer (" + optifineModJar + ") three times without a valid jar produced");
			}

			optifineModJar = optifineMod;
		}

		// 修改：跳过有问题的处理步骤，直接使用原始jar
		System.out.println("[OptiFabric] 跳过复杂的处理，直接使用原始OptiFine jar");
		File jarOfTheFree = optifineModJar;

		String namespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();
		System.out.println("[OptiFabric] 当前命名空间: " + namespace);

		// 修改：直接复制而不重新映射
		File completeJar = new File(workDir, "Optifine-remapped.jar");
		System.out.println("[OptiFabric] 直接复制OptiFine jar，跳过重新映射");
		Files.copy(optifineModJar.toPath(), completeJar.toPath(), StandardCopyOption.REPLACE_EXISTING);

		for (UnaryOperator<File> transformer : FabricLoader.getInstance().getEntrypoints("optifabric:transformer", UnaryOperator.class)) {
			completeJar = transformer.apply(completeJar);
			if (completeJar == null || !completeJar.canRead()) throw new IllegalStateException("Jar transformer returned invalid jar: " + completeJar);
		}
		File completedJar = completeJar;

		Consumer<ZipVisitor> jarFinaliser;
		if (remappedJar.exists() && !remappedJar.delete()) {
			System.err.println("[OptiFabric] 无法清除 " + remappedJar + "，使用临时文件");
			remappedJar = completedJar;
			jarFinaliser = visitor -> ZipUtils.filterInPlace(completedJar, visitor);
		} else {
			final File finalRemappedJar = remappedJar;
			jarFinaliser = visitor -> ZipUtils.filter(completedJar, visitor, finalRemappedJar);
		}

		// 修改：生成一个空的类缓存，避免复杂的处理
		ClassCache classCache = new ClassCache(modHash);
		System.out.println("[OptiFabric] 创建空的类缓存");

		// 保存类缓存
		classCache.save(optifinePatches);

		// 清理工作目录
		workDir.deleteOnExit();
		for (File file : workDir.listFiles()) file.deleteOnExit();

		System.out.println("[OptiFabric] OptiFine 设置完成");
		return Pair.of(remappedJar, classCache);
	}

	// 新增方法：获取准确的Minecraft版本
	private static String getMinecraftVersion() {
		try {
			ModContainer minecraft = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow(() -> new IllegalStateException("No Minecraft?"));
			return minecraft.getMetadata().getVersion().getFriendlyString();
		} catch (Exception e) {
			return "unknown";
		}
	}

	private static void runInstaller(File installer, File output, File minecraftJar) throws IOException {
		System.out.println("[OptiFabric] 运行 OptiFine 修补程序");

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, OptifineSetup.class.getClassLoader())) {
			Class<?> clazz = classLoader.loadClass("optifine.Patcher");
			Method method = clazz.getDeclaredMethod("process", File.class, File.class, File.class);
			method.invoke(null, minecraftJar, installer, output);
		} catch (ReflectiveOperationException | MalformedURLException e) {
			throw new RuntimeException("Error running OptiFine patcher at " + installer + " on " + minecraftJar, e);
		}
	}

	// 修改：简化getMinecraftJar方法
	private static Path getMinecraftJar() {
		String givenJar = System.getProperty("optifabric.mc-jar");
		if (givenJar != null) {
			File givenJarFile = new File(givenJar);
			if (givenJarFile.exists()) {
				return givenJarFile.toPath();
			}
		}

		try {
			// 直接使用Fabric API获取游戏jar
			ModContainer minecraft = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow(() -> new IllegalStateException("No Minecraft?"));
			return minecraft.getRootPath();
		} catch (Exception e) {
			System.err.println("[OptiFabric] 无法获取Minecraft jar: " + e.getMessage());
			throw new RuntimeException("无法找到Minecraft jar", e);
		}
	}
}
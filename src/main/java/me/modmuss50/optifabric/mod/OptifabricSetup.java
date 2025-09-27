package me.modmuss50.optifabric.mod;

import java.io.File;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.common.base.MoreObjects;

import org.apache.commons.lang3.tuple.Pair;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.util.version.SemanticVersionImpl;
import net.fabricmc.loader.util.version.SemanticVersionPredicateParser;

import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.util.RemappingUtils;

import com.chocohead.mm.api.ClassTinkerers;

public class OptifabricSetup implements Runnable {
	public static File optifineRuntimeJar = null;
	public static boolean usingScreenAPI;

	@Override
	public void run() {
		OptifineInjector injector;
		try {
			Pair<File, ClassCache> runtime = OptifineSetup.getRuntime();
			optifineRuntimeJar = runtime.getLeft();

			ClassTinkerers.addURL(runtime.getLeft().toURI().toURL());

			injector = new OptifineInjector(runtime.getRight());
			injector.setup();
		} catch (Throwable e) {
			if (!OptifabricError.hasError()) {
				OptifineVersion.jarType = JarType.INTERNAL_ERROR;
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
	
	public static boolean isPresent(String modId, String versionRange) {
		Optional<net.fabricmc.loader.api.ModContainer> modContainer = FabricLoader.getInstance().getModContainer(modId);
		if (!modContainer.isPresent()) {
			return false;
		}

		ModMetadata metadata = modContainer.get().getMetadata();
		Version currentVersion;
		try {
			currentVersion = Version.parse(metadata.getVersion().getFriendlyString());
			// 简单的版本范围检查实现
			if (versionRange.startsWith(">=")) {
				String minVersionStr = versionRange.substring(2);
				Version minVersion = Version.parse(minVersionStr);
				return currentVersion.compareTo(minVersion) >= 0;
			} else if (versionRange.startsWith(">")) {
				String minVersionStr = versionRange.substring(1);
				Version minVersion = Version.parse(minVersionStr);
				return currentVersion.compareTo(minVersion) > 0;
			}
			return true;
		} catch (VersionParsingException e) {
			return false;
		}
	}

	// 移除版本检查方法（不再需要）
}
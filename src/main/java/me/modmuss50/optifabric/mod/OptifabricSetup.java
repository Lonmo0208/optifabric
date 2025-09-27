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

import me.modmuss50.optifabric.mod.OptifineVersion.JarType;
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

		BooleanSupplier particlesPresent = new FeatureFinder() {
			@Override
			protected boolean isPresent() {
				return injector.predictFuture(RemappingUtils.getClassName("class_702")).filter(node -> {
					String desc = RemappingUtils.mapMethodDescriptor("(Lnet/minecraft/class_4587;Lnet/minecraft/class_4597$class_4598;"
							+ "Lnet/minecraft/class_765;Lnet/minecraft/class_4184;FLnet/minecraft/class_4604;)V");

					for (MethodNode method : node.methods) {
						if (("renderParticles".equals(method.name) || "render".equals(method.name)) && desc.equals(method.desc)) {
							return true;
						}
					}
					return false;
				}).isPresent();
			}
		};
		BooleanSupplier farPlanePresent = new FeatureFinder() {
			@Override
			protected boolean isPresent() {
				return injector.predictFuture(RemappingUtils.getClassName("class_757")).filter(node -> {
					String render = RemappingUtils.getMethodName("class_757", "method_3192", "(FJZ)V");

					for (MethodNode method : node.methods) {
						if (render.equals(method.name) && "(FJZ)V".equals(method.desc)) {
							for (AbstractInsnNode insn : method.instructions) {
								if (insn.getType() == AbstractInsnNode.FIELD_INSN && "ForgeHooksClient_getGuiFarPlane".equals(((FieldInsnNode) insn).name)) {
									return true;
								}
							}
							break;
						}
					}
					return false;
				}).isPresent();
			}
		};
		BooleanSupplier setupFogPresent = new FeatureFinder() {
			@Override
			protected boolean isPresent() {
				return injector.predictFuture(RemappingUtils.getClassName("class_758")).filter(node -> {
					String desc = RemappingUtils.mapMethodDescriptor("(Lnet/minecraft/class_4184;Lnet/minecraft/class_758$class_4596;FZF)V");

					for (MethodNode method : node.methods) {
						if ("setupFog".equals(method.name) && desc.equals(method.desc)) {
							return true;
						}
					}
					return false;
				}).isPresent();
			}
		};

		// 移除所有版本检查，强制加载所有兼容配置
		if (isPresent("fabric-renderer-api-v1")) {
			Mixins.addConfiguration("optifabric.compat.fabric-renderer-api.new-mixins.json");
		}

		if (isPresent("fabric-rendering-v1", ">=1.5.0") && particlesPresent.getAsBoolean()) {
			Mixins.addConfiguration("optifabric.compat.fabric-rendering.new-mixins.json");
		}
		if (isPresent("fabric-rendering-v1", ">=1.13.0 <2.0") || isPresent("fabric-rendering-v1", ">=2.1.0")) {
			Mixins.addConfiguration("optifabric.compat.fabric-rendering.extra-mixins.json");
		}

		if (isPresent("fabric-rendering-data-attachment-v1")) {
			Mixins.addConfiguration("optifabric.compat.fabric-rendering-data.mixins.json");

			if (true) { // 强制启用
				injector.predictFuture(RemappingUtils.getClassName("class_6850")).ifPresent(node -> {
					String desc = RemappingUtils.mapMethodDescriptor("(Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_2338;IZ)Lnet/minecraft/class_853;");

					for (MethodNode method : node.methods) {
						if ("createRegion".equals(method.name) && desc.equals(method.desc)) {
							Mixins.addConfiguration("optifabric.compat.fabric-rendering-data.bonus-mixins.json");
							break;
						}
					}
				});
			} else if (true) { // 强制启用
				injector.predictFuture(RemappingUtils.getClassName("class_853")).ifPresent(node -> {
					String desc = RemappingUtils.mapMethodDescriptor("(Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_2338;IZ)Lnet/minecraft/class_853;");

					for (MethodNode method : node.methods) {
						if ("generateCache".equals(method.name) && desc.equals(method.desc)) {
							Mixins.addConfiguration("optifabric.compat.fabric-rendering-data.extra-mixins.json");
							break;
						}
					}
				});
			}
		}

		if (isPresent("fabric-renderer-indigo")) {
			injector.predictFuture(RemappingUtils.getClassName("class_776")).ifPresent(node -> {
				String desc = RemappingUtils.getClassName("class_1921").concat(";)V");

				for (MethodNode method : node.methods) {
					if ("renderBatched".equals(method.name) && method.desc.endsWith(desc)) {
						Mixins.addConfiguration("optifabric.compat.indigo.newer-mixins.json");
						return;
					}
				}
				Mixins.addConfiguration("optifabric.compat.indigo.new-mixins.json");
			});
		}

		if (isPresent("fabric-item-api-v1", ">=1.1.0")) {
			Mixins.addConfiguration("optifabric.compat.fabric-item-api.mixins.json");
		}

		if (isPresent("fabric-screen-api-v1")) {
			Mixins.addConfiguration("optifabric.compat.fabric-screen-api.new4er-mixins.json");
			usingScreenAPI = true;
		}

		if (isPresent("fabric-lifecycle-events-v1")) {
			Mixins.addConfiguration("optifabric.compat.fabric-lifecycle-events.new-mixins.json");
		}

		Mixins.addConfiguration("optifabric.optifine.mixins.json");
		Mixins.addConfiguration("optifabric.optifine.old-mixins.json");
	}

	// 保留原有isPresent方法但忽略版本检查
	public static boolean isPresent(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	public static boolean isPresent(String modId, String versionRange) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}
}
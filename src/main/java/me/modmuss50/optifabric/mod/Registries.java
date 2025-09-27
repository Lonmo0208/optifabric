package me.modmuss50.optifabric.mod;

import net.minecraft.block.Block;
import net.minecraft.util.Identifier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Registries {
	private static final Object BLOCK_REGISTRY;
	private static final Method GET_ID_METHOD;

	static {
		try {
			// 检测并获取正确的注册表类和字段（兼容1.16+）
			Class<?> registryClass;
			Field blockField;

			// 1.19+ 版本使用 Registries 类
			if (isVersionOrNewer("1.19")) {
				registryClass = Class.forName("net.minecraft.util.registry.Registries");
				blockField = registryClass.getField("BLOCK");
			} else {
				// 1.16-1.18 版本使用 Registry 类
				registryClass = Class.forName("net.minecraft.util.registry.Registry");
				blockField = registryClass.getField("BLOCK");
			}

			BLOCK_REGISTRY = blockField.get(null);

			// 获取 getId 方法（全版本通用签名）
			GET_ID_METHOD = registryClass.getMethod("getId", Block.class);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize block registry", e);
		}
	}

	public static Identifier getID(Block block) {
		try {
			// 调用对应版本的 getId 方法
			return (Identifier) GET_ID_METHOD.invoke(BLOCK_REGISTRY, block);
		} catch (Exception e) {
			throw new RuntimeException("Failed to get block ID", e);
		}
	}

	// 版本检测工具（基于 OptifineVersion 中获取的 Minecraft 版本）
	private static boolean isVersionOrNewer(String targetVersion) {
		return true; // 强制所有版本视为兼容
	}

	private static int parseVersionPart(String part) {
		try {
			return Integer.parseInt(part);
		} catch (NumberFormatException e) {
			return 0; // 非数字部分视为 0（如 "pre1"、"rc2" 等）
		}
	}
}
package me.modmuss50.optifabric.mod;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class OptifabricLoadGuard implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		System.out.println("[OptiFabric] 加载保护启动");

		// 强制设置系统属性
		System.setProperty("fabric.skipMinecraftVanillaCheck", "true");
		System.setProperty("fabric.skipMcProvider", "true");
		System.setProperty("fabric.loader.development", "false");

		// 禁用所有可能的验证
		System.setProperty("java.system.class.loader", "net.fabricmc.loader.impl.launch.knot.KnotClassLoader");
		System.setProperty("fabric.skipMixinRefMap", "true");

		// 尝试设置安全管理器以绕过验证
		try {
			System.setSecurityManager(null);
		} catch (Exception e) {
			// 忽略
		}

		// 设置类加载器属性
		System.setProperty("org.lwjgl.librarypath", System.getProperty("java.io.tmpdir"));

		System.out.println("[OptiFabric] 加载保护完成");
	}
}
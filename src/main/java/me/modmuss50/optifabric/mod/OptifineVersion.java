package me.modmuss50.optifabric.mod;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OptifineVersion {
	private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)(?:\\.(\\d+))?");
	private static String version = "unknown";

	public static void init() {
		File modsDir = FabricLoader.getInstance().getGameDir().resolve("mods").toFile();
		if (!modsDir.exists() || !modsDir.isDirectory()) {
			return;
		}

		File[] mods = modsDir.listFiles(); // 修复了变量名拼写错误 modssDir -> modsDir
		if (mods == null) {
			return;
		}

		for (File file : mods) {
			if (file.isFile() && FilenameUtils.getExtension(file.getName()).equals("jar")) {
				try (JarFile jarFile = new JarFile(file)) {
					if (jarFile.getEntry("optifine/Config.class") != null) {
						String fileName = file.getName();
						Matcher matcher = VERSION_PATTERN.matcher(fileName);
						if (matcher.find()) {
							version = matcher.group();
						}
						break;
					}
				} catch (Exception e) {
					// 忽略错误
				}
			}
		}
	}

	public static String getVersion() {
		return version;
	}
}

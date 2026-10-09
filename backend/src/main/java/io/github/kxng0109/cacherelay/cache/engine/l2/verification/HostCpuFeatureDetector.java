package io.github.kxng0109.cacherelay.cache.engine.l2.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Zero-dependency CPU instruction set architecture (ISA) detector.
 *
 * <p>Probes host CPU capabilities to determine if hardware acceleration for quantized INT8 tensor
 * operations is present:
 * <ul>
 *   <li><b>Linux:</b> Scans {@code /proc/cpuinfo} for {@code avx512_vnni}, {@code avx_vnni},
 *       {@code asimddp}, or {@code i8mm} flags.</li>
 *   <li><b>macOS:</b> Queries {@code sysctl} for {@code hw.optional.arm.FEAT_DotProd} or
 *       {@code hw.optional.arm.FEAT_I8MM}.</li>
 *   <li><b>Windows / Other:</b> Falls back safely to {@link CpuAccelerationTier#BASELINE_FP32}.</li>
 * </ul>
 */
@Component
public class HostCpuFeatureDetector {

	private static final Logger log = LoggerFactory.getLogger(HostCpuFeatureDetector.class);

	private static final Path DEFAULT_LINUX_CPUINFO = Path.of("/proc/cpuinfo");

	private static final Set<String> ACCELERATED_FLAGS = Set.of(
			"avx512_vnni",
			"avx_vnni",
			"asimddp",
			"i8mm"
	);

	private final CpuAccelerationTier detectedTier;

	/**
	 * Default constructor performing detection on the host system.
	 */
	public HostCpuFeatureDetector() {
		this(
				System.getProperty("os.name", ""),
				DEFAULT_LINUX_CPUINFO,
				HostCpuFeatureDetector::runSysctl
		);
	}

	/**
	 * Package-private constructor for deterministic testing across OS environments.
	 *
	 * @param osName           operating system name
	 * @param linuxCpuInfoPath path to cpuinfo file on Linux
	 * @param sysctlRunner     function to execute sysctl queries on macOS
	 */
	HostCpuFeatureDetector(
			String osName,
			Path linuxCpuInfoPath,
			Function<String, String> sysctlRunner
	) {
		this.detectedTier = detectTier(osName, linuxCpuInfoPath, sysctlRunner);
		log.info("Host CPU acceleration tier detected: {}", this.detectedTier);
	}

	/**
	 * Returns the detected acceleration tier for this host.
	 *
	 * @return detected tier (either {@link CpuAccelerationTier#ACCELERATED_INT8} or {@link CpuAccelerationTier#BASELINE_FP32})
	 */
	public CpuAccelerationTier detectTier() {
		return detectedTier;
	}

	static CpuAccelerationTier detectTier(
			String osName,
			Path linuxCpuInfoPath,
			Function<String, String> sysctlRunner
	) {
		String lowerOs = osName.toLowerCase(Locale.ROOT);
		if (lowerOs.contains("linux")) {
			return detectLinux(linuxCpuInfoPath);
		} else if (lowerOs.contains("mac") || lowerOs.contains("darwin")) {
			return detectMac(sysctlRunner);
		}
		return CpuAccelerationTier.BASELINE_FP32;
	}

	static CpuAccelerationTier detectLinux(Path cpuInfoPath) {
		if (!Files.isReadable(cpuInfoPath)) {
			return CpuAccelerationTier.BASELINE_FP32;
		}
		try (BufferedReader reader = Files.newBufferedReader(cpuInfoPath, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				String lower = line.toLowerCase(Locale.ROOT).trim();
				if (lower.startsWith("flags") || lower.startsWith("features")) {
					int colon = lower.indexOf(':');
					if (colon >= 0) {
						String flagsStr = lower.substring(colon + 1);
						for (String flag : flagsStr.split("\\s+")) {
							if (ACCELERATED_FLAGS.contains(flag)) {
								return CpuAccelerationTier.ACCELERATED_INT8;
							}
						}
					}
				}
			}
		} catch (Exception ex) {
			log.debug("Failed reading Linux cpuinfo: {}", ex.getMessage());
		}
		return CpuAccelerationTier.BASELINE_FP32;
	}

	static CpuAccelerationTier detectMac(Function<String, String> sysctlRunner) {
		try {
			String dotProd = sysctlRunner.apply("hw.optional.arm.FEAT_DotProd");
			if ("1".equals(dotProd.trim())) {
				return CpuAccelerationTier.ACCELERATED_INT8;
			}
			String i8mm = sysctlRunner.apply("hw.optional.arm.FEAT_I8MM");
			if ("1".equals(i8mm.trim())) {
				return CpuAccelerationTier.ACCELERATED_INT8;
			}
		} catch (Exception ex) {
			log.debug("Failed executing macOS sysctl query: {}", ex.getMessage());
		}
		return CpuAccelerationTier.BASELINE_FP32;
	}

	static String runSysctl(String param) {
		return runSysctlProcess(new ProcessBuilder("sysctl", "-n", param));
	}

	static String runSysctlProcess(ProcessBuilder pb) {
		Process process = null;
		try {
			process = pb.start();
			boolean finished = process.waitFor(2, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return "";
			}
			if (process.exitValue() == 0) {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder out = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						out.append(line);
					}
					return out.toString().trim();
				}
			}
		} catch (Exception ex) {
			log.debug("Sysctl process invocation failed: {}", ex.getMessage());
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
		return "";
	}
}

package io.github.kxng0109.cacherelay.cache.engine.l2.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostCpuFeatureDetector CPU ISA feature detection")
class HostCpuFeatureDetectorTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("Linux detects avx512_vnni flag as ACCELERATED_INT8")
	void linuxDetectsAvx512Vnni() throws IOException {
		Path cpuinfo = Files.writeString(tempDir.resolve("cpuinfo_avx512"),
				"processor\t: 0\nflags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep avx512_vnni sse4_2\n");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", cpuinfo, cmd -> "");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("Linux detects avx_vnni flag as ACCELERATED_INT8")
	void linuxDetectsAvxVnni() throws IOException {
		Path cpuinfo = Files.writeString(tempDir.resolve("cpuinfo_avx_vnni"),
				"processor\t: 0\nflags\t\t: fpu vme avx_vnni sse4_1 sse4_2 popcnt aes\n");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", cpuinfo, cmd -> "");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("Linux detects ARM asimddp feature as ACCELERATED_INT8")
	void linuxDetectsAsimddp() throws IOException {
		Path cpuinfo = Files.writeString(tempDir.resolve("cpuinfo_arm"),
				"processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 asimddp\n");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", cpuinfo, cmd -> "");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("Linux detects ARM i8mm feature as ACCELERATED_INT8")
	void linuxDetectsI8mm() throws IOException {
		Path cpuinfo = Files.writeString(tempDir.resolve("cpuinfo_i8mm"),
				"processor\t: 0\nFeatures\t: fp asimd evtstrm i8mm sha3 sm3 sm4\n");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", cpuinfo, cmd -> "");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("Linux without VNNI or dotprod flags falls back to BASELINE_FP32")
	void linuxFallbackBaseline() throws IOException {
		Path cpuinfo = Files.writeString(tempDir.resolve("cpuinfo_baseline"),
				"processor\t: 0\nflags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sse sse2 sse3\n");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", cpuinfo, cmd -> "");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("Linux with missing cpuinfo file falls back to BASELINE_FP32")
	void linuxMissingCpuinfo() {
		Path missing = tempDir.resolve("non_existent_cpuinfo");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", missing, cmd -> "");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("macOS detects hw.optional.arm.FEAT_DotProd as ACCELERATED_INT8")
	void macDetectsDotProd() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Mac OS X", tempDir,
				cmd -> "hw.optional.arm.FEAT_DotProd".equals(cmd) ? "1" : "0");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("macOS detects hw.optional.arm.FEAT_I8MM as ACCELERATED_INT8")
	void macDetectsI8mm() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Darwin", tempDir,
				cmd -> "hw.optional.arm.FEAT_I8MM".equals(cmd) ? "1" : "0");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("macOS without hardware acceleration flags falls back to BASELINE_FP32")
	void macFallbackBaseline() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Mac OS X", tempDir, cmd -> "0");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("macOS handling sysctl runner exception falls back to BASELINE_FP32")
	void macSysctlExceptionFallback() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Mac OS X", tempDir, cmd -> {
			throw new RuntimeException("sysctl not found");
		});

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("Windows falls back safely to BASELINE_FP32")
	void windowsFallbackBaseline() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Windows 11", tempDir, cmd -> "1");

		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("runSysctl handles process execution or missing binary gracefully")
	void runSysctlGraceful() {
		String result = HostCpuFeatureDetector.runSysctl("hw.model");
		assertThat(result).isNotNull();

		// Process returning exit code 0 and output (cross-platform)
		boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
		ProcessBuilder pb = isWindows
				? new ProcessBuilder("cmd.exe", "/c", "echo 1")
				: new ProcessBuilder("echo", "1");
		String echoOut = HostCpuFeatureDetector.runSysctlProcess(pb);
		assertThat(echoOut).isEqualTo("1");

		// Process returning non-zero exit code (cross-platform)
		ProcessBuilder failPb = isWindows
				? new ProcessBuilder("cmd.exe", "/c", "exit 1")
				: new ProcessBuilder("sh", "-c", "exit 1");
		String exitFailOut = HostCpuFeatureDetector.runSysctlProcess(failPb);
		assertThat(exitFailOut).isEmpty();

		// Invalid binary throwing exception
		String invalidOut = HostCpuFeatureDetector.runSysctlProcess(new ProcessBuilder("non_existent_binary_xyz"));
		assertThat(invalidOut).isEmpty();
	}

	@Test
	@DisplayName("Linux parser handles lines without colon and non-flag lines")
	void linuxCpuinfoFormatVariations() throws IOException {
		Path cpuinfo = Files.writeString(tempDir.resolve("cpuinfo_weird"),
				"model name : Some CPU\nflags_no_colon\nflags\nfeatures\nflags: unrelated_flag another_flag\n");

		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("Linux", cpuinfo, cmd -> "");
		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("Unknown or unsupported OS falls back safely to BASELINE_FP32")
	void unsupportedOsFallback() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector("FreeBSD", tempDir, cmd -> "1");
		assertThat(detector.detectTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}
	void defaultConstructorDetectsValidTier() {
		HostCpuFeatureDetector detector = new HostCpuFeatureDetector();

		assertThat(detector.detectTier()).isIn(
				CpuAccelerationTier.ACCELERATED_INT8,
				CpuAccelerationTier.BASELINE_FP32
		);
	}
}

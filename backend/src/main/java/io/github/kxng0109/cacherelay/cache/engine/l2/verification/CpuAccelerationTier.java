package io.github.kxng0109.cacherelay.cache.engine.l2.verification;

/**
 * CPU hardware acceleration tier for ONNX model execution.
 */
public enum CpuAccelerationTier {

	/**
	 * Automatically probe the host CPU ISA and select the optimal acceleration tier.
	 */
	AUTO,

	/**
	 * Accelerated INT8 execution using specialized hardware vector instructions
	 * (e.g. AVX512_VNNI, AVX_VNNI on x86, ASIMDDP / I8MM on ARM64).
	 */
	ACCELERATED_INT8,

	/**
	 * Baseline FP32 execution without quantized integer vector extensions.
	 */
	BASELINE_FP32
}

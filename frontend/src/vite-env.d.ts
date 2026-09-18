/// <reference types="vite/client" />

/**
 * Typed client environment. Every `VITE_*` key the SPA reads is declared
 * here so `import.meta.env` never degrades to `any`; undeclared keys are a
 * type error, and all values remain non-secret build-time config.
 */
interface ImportMetaEnv {
  /** Gateway base URL (no trailing slash). Absent or empty means same-origin. */
  readonly VITE_API_BASE_URL?: string
  /** `false` forces non-streaming JSON completions; anything else streams. */
  readonly VITE_FEATURE_STREAMING?: string
}

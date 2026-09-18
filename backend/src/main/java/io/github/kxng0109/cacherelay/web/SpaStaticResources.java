package io.github.kxng0109.cacherelay.web;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache policy for the operator SPA static assets.
 *
 * <p>Vite emits content-hashed filenames under {@code /assets/}, so those bytes are immutable: one year,
 * {@code public}, {@code immutable}. The shell ({@code /index.html}) is served by Boot defaults with no explicit
 * policy so browsers always revalidate it; a stale shell referencing rotated asset hashes is the classic SPA
 * cache failure this split avoids.
 *
 * @since 1.8.0
 */
@Configuration
public class SpaStaticResources implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/assets/**")
				.addResourceLocations("classpath:/static/assets/")
				.setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
		registry.addResourceHandler("/index.html")
				.addResourceLocations("classpath:/static/")
				.setCacheControl(CacheControl.noStore());
	}
}

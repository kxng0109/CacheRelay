package io.github.kxng0109.cacherelay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
@EnableJpaRepositories(basePackages = {"io.github.kxng0109.cacherelay.ledger",
		"io.github.kxng0109.cacherelay.budget", "io.github.kxng0109.cacherelay.replay",
		"io.github.kxng0109.cacherelay.auth", "io.github.kxng0109.cacherelay.model"})
public class CacheRelayApplication {

	static void main(String[] args) {
		SpringApplication.run(CacheRelayApplication.class, args);
	}

}

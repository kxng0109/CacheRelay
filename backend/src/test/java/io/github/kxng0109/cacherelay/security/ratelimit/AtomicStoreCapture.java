package io.github.kxng0109.cacherelay.security.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Captures atomic-store script ARGV in key-management tests (ADM-B14): creation
 * persists the hash and the index entry in one Lua execution, so tests assert on
 * the script arguments instead of split {@code putAll}/{@code sadd} writes.
 */
final class AtomicStoreCapture {

	private AtomicStoreCapture() {
	}

	/**
	 * Stubs the atomic-store script to succeed while recording its flat ARGV.
	 *
	 * @param template mocked template
	 * @return reference holding the captured arguments after the call
	 */
	static AtomicReference<List<Object>> captureScriptArgv(StringRedisTemplate template) {
		AtomicReference<List<Object>> argv = new AtomicReference<>(List.of());
		when(template.execute(any(), anyList(), any(Object[].class))).thenAnswer(inv -> {
			Object[] all = inv.getArguments();
			List<Object> flat = new ArrayList<>();
			for (int i = 2; i < all.length; i++) {
				Object element = all[i];
				if (element instanceof Object[] nested) {
					flat.addAll(Arrays.asList(nested));
				} else {
					flat.add(element);
				}
			}
			argv.set(flat);
			return 1L;
		});
		return argv;
	}

	/**
	 * Rebuilds the stored field map from atomic-store script ARGV (flat key/value
	 * pairs plus the trailing index hex).
	 *
	 * @param argv captured script arguments
	 * @return field map as the hash would store it
	 */
	static Map<String, String> scriptFields(List<Object> argv) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (int i = 0; i + 1 < argv.size() - 1; i += 2) {
			fields.put(String.valueOf(argv.get(i)), String.valueOf(argv.get(i + 1)));
		}
		return fields;
	}
}

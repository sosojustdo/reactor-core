/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.Schedulers.Factory;
import reactor.util.annotation.Nullable;

import static io.micrometer.core.instrument.Metrics.globalRegistry;

/**
 * Utilities around instrumentation and metrics with Micrometer.
 *
 * @author Simon Baslé
 */
public class Metrics {

	private static final boolean isMicrometerAvailable;

	static {
		boolean micrometer;
		try {
			globalRegistry.getRegistries();
			micrometer = true;
		}
		catch (Throwable t) {
			micrometer = false;
		}
		isMicrometerAvailable = micrometer;
	}

	@Nullable
	static MeterRegistry defaultRegistry;

	/**
	 * @return true if the Micrometer instrumentation facade is available
	 */
	public static final boolean isMicrometerAvailable() {
		return isMicrometerAvailable;
	}

	private static final void resetRegistry() {
		MeterRegistry old = Metrics.defaultRegistry;
		Metrics.defaultRegistry = null;
		if (old != null) {
			old.close();
		}
	}

	/**
	 * Define a Micrometer MeterRegistry, passed as a simple {@link Object}, to be used by
	 * instrumentation-related features of Reactor. If Micrometer is not available on the
	 * classpath OR the passed object is not a registry, returns {@code false} and does nothing.
	 * <p>
	 * If you have already defined a valid default meter registry previously, said registry
	 * will be closed on your behalf.
	 *
	 * @implNote The candidate registry is exposed as an Object in order to avoid a hard
	 * dependency of Reactor on Micrometer.
	 *
	 * @param registryCandidate the {@link Object} that is potentially a Micrometer registry,
	 * or null to reset to the default of using the Micrometer global registry.
	 * @return true if the object was a registry (and thus Micrometer is available), false otherwise.
	 */
	public static boolean setRegistryCandidate(@Nullable Object registryCandidate) {
		if (registryCandidate == null) {
			resetRegistry();
			return true;
		}
		if (isMicrometerAvailable && registryCandidate instanceof MeterRegistry) {
			resetRegistry();
			Metrics.defaultRegistry = (MeterRegistry) registryCandidate;
			return true;
		}
		return false;
	}

	/**
	 * Return a candidate Micrometer registry. If {@link #setRegistryCandidate(Object)} was
	 * called with a valid registry instance, this method returns said instance. Otherwise
	 * it returns {@code null}.
	 *
	 * @implNote The candidate registry is exposed as an Object in order to avoid a hard
	 * dependency of Reactor on Micrometer.
	 *
	 * @return the default Micrometer registry to be used by Reactor, or null if not set / not available.
	 */
	@Nullable
	public static Object getRegistryCandidate() {
		return defaultRegistry;
	}

	/**
	 * If the Micrometer instrumentation facade is available, return a simple {@link Scheduler}
	 * {@link Factory} that instruments {@link ExecutorService}-based schedulers (as
	 * supported by Micrometer, ie. it instruments state of queues but not timing of tasks).
	 * Use {@link Schedulers#setFactory(Factory)} to activate it.
	 * <p>
	 * This factory sends instrumentation data to the Micrometer Global Registry.
	 *
	 * @implNote Note that if you need to define a {@link Factory} for other purposes, or if you use
	 * a library that relies on a specific {@link Factory} behavior, this implementation is
	 * not suitable, as it reintroduces the default behavior on most methods. You might want
	 * to consider implementing a composite pattern, or copying the behavior of this factory.
	 *
	 * @return a new {@link Factory} that instruments internal state of {@link ExecutorService} backing
	 * some standard {@link Scheduler Schedulers}, or the default {@link Factory} if Micrometer isn't available.
	 */
	public static Factory instrumentedSchedulers() {
		if (isMicrometerAvailable) {
			return new MicrometerSchedulersFactory();
		}
		return new Factory() {};
	}

	static final class MicrometerSchedulersFactory implements Factory {

		private Map<String, Long> seenSchedulers = new HashMap<>();

		@Override
		public ScheduledExecutorService decorateExecutorService(String schedulerType,
				Supplier<? extends ScheduledExecutorService> actual) {
			ScheduledExecutorService service = actual.get();

			Long number = seenSchedulers.compute(schedulerType, (it, key) -> key == null ? 1 : key + 1);
			String executorNumber = schedulerType + "-exec" + number;
			MeterRegistry registry = Metrics.defaultRegistry;
			if (registry == null) {
				registry = globalRegistry;
			}
			ExecutorServiceMetrics.monitor(registry, service, schedulerType,
					Tag.of("scheduler", actual.toString()),
					Tag.of("executorId", executorNumber));
			return service;
		}
	}

}
